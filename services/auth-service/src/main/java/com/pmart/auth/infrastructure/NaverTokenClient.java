package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * 네이버 토큰 엔드포인트({@code POST {token-base-url}/oauth2/token}, client_secret_post) — 인가 코드를 ID 토큰으로
 * 교환한다(2026-11-20 ADR-0078, BR-69). 응답의 {@code id_token} 만 돌려주고 access/refresh token 은 읽지 않고 버린다.
 *
 * <p>공용 RestClient.Builder 를 쓰지 않는다 — 사용자 토큰 전달 설정이 밖(네이버)으로 새지 않게(customer-service OctomoClient 와
 * 같은 방식). 코드·verifier·secret·토큰은 로그에 남기지 않고, 네이버 오류는 오류 코드 문자열만 남긴다.
 *
 * <ul>
 *   <li>4xx 또는 200 본문의 {@code error}(invalid_grant·invalid_client 등), id_token 없음 → {@link
 *       Rejected}
 *   <li>연결 실패·시간 초과·5xx·해석할 수 없는 응답 → {@link Unavailable}
 * </ul>
 */
@Component
public class NaverTokenClient {

  private static final Logger log = LoggerFactory.getLogger(NaverTokenClient.class);

  /** 로그에 남겨도 되는 오류 코드 모양(OAuth error 값). 그 밖은 "unknown". */
  private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,40}");

  /** 네이버가 거절했다(인가 코드·verifier·redirect_uri·클라이언트 문제). */
  public static class Rejected extends RuntimeException {
    public Rejected(String errorCode) {
      super("네이버 토큰 교환 거절: " + errorCode);
    }
  }

  /** 네이버 응답을 받지 못했다(재시도 가능). */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String reason, @Nullable Throwable cause) {
      super("네이버 토큰 교환 불가: " + reason, cause);
    }
  }

  /** 교환 요청 값. toString 은 값을 가린다(로그 사고 방지). */
  public record Request(
      String clientId,
      String clientSecret,
      String code,
      String codeVerifier,
      String redirectUri,
      @Nullable String state) {
    @Override
    public String toString() {
      return "NaverTokenClient.Request[***]";
    }
  }

  private final RestClient http;

  public NaverTokenClient(CustomerProperties props) {
    Duration timeout = props.naverExchange().tokenTimeout();
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // 시험 WireMock(평문 http)에서 h2c 업그레이드 시도 없이
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    factory.setReadTimeout(timeout);
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * @param tokenBaseUrl 기준 주소(끝 / 없음)
   * @return 네이버 OIDC id_token
   */
  public String exchange(String tokenBaseUrl, Request r) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", r.clientId());
    form.add("client_secret", r.clientSecret());
    form.add("code", r.code());
    form.add("code_verifier", r.codeVerifier());
    form.add("redirect_uri", r.redirectUri());
    if (r.state() != null && !r.state().isBlank()) {
      form.add("state", r.state());
    }
    JsonNode body;
    try {
      body =
          http.post()
              .uri(tokenBaseUrl + "/oauth2/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
    } catch (HttpClientErrorException e) {
      String code = errorCode(e);
      log.warn("네이버 토큰 교환 거절 status={} error={}", e.getStatusCode().value(), code);
      throw new Rejected(code);
    } catch (HttpStatusCodeException e) {
      log.warn("네이버 토큰 교환 실패 status={}", e.getStatusCode().value());
      throw new Unavailable("HTTP " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      log.warn(
          "네이버 토큰 교환 연결 실패: {} ({})",
          e.getClass().getSimpleName(),
          e.getMostSpecificCause().getClass().getSimpleName());
      throw new Unavailable(e.getClass().getSimpleName(), e);
    }
    if (body == null || !body.isObject()) {
      log.warn("네이버 토큰 교환 응답이 비었거나 객체가 아님");
      throw new Unavailable("empty response", null);
    }
    if (body.hasNonNull("error")) {
      // 네이버는 거절도 200 + {"error": …} 로 줄 수 있다
      String code = safe(body.get("error").asString(""));
      log.warn("네이버 토큰 교환 거절 status=200 error={}", code);
      throw new Rejected(code);
    }
    String idToken = body.path("id_token").asString("");
    if (idToken.isBlank()) {
      // scope 에 openid 가 없던 인가 등 — 코드는 이미 쓰였으니 다시 인가해야 한다
      log.warn("네이버 토큰 교환 응답에 id_token 없음(scope openid 확인)");
      throw new Rejected("missing_id_token");
    }
    return idToken;
  }

  private static String errorCode(HttpClientErrorException e) {
    try {
      JsonNode problem = e.getResponseBodyAs(JsonNode.class);
      if (problem != null && problem.hasNonNull("error")) {
        return safe(problem.get("error").asString(""));
      }
    } catch (RuntimeException ignored) {
      // 본문 해석 실패는 unknown
    }
    return "unknown";
  }

  private static String safe(String code) {
    return ERROR_CODE.matcher(code).matches() ? code : "unknown";
  }
}
