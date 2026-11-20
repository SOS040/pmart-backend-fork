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
 * 교환한다(2026-11-20 ADR-0078, BR-69). 응답의 {@code id_token} 과 access token 을 돌려주고 refresh token 은 읽지
 * 않는다. access token 은 호출한 쪽이 프로필 API({@link #profileImage}, BR-70)에 한 번 쓰고 버린다 — 저장·응답·로그 금지.
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

  /**
   * 교환 결과. access token 은 프로필 API 한 번에만 쓴다(없을 수 있음). toString 은 값을 가린다.
   *
   * @param idToken 네이버 OIDC id_token
   * @param accessToken 네이버 access token(없으면 null)
   */
  public record Tokens(String idToken, @Nullable String accessToken) {
    @Override
    public String toString() {
      return "NaverTokenClient.Tokens[***]";
    }
  }

  /** 프로필 API 응답 대기 상한 — 로그인 앞길이라 짧게(token-timeout 이 더 짧으면 그 값). */
  static final Duration PROFILE_TIMEOUT_MAX = Duration.ofSeconds(3);

  private final RestClient http;
  private final RestClient profileHttp;

  public NaverTokenClient(CustomerProperties props) {
    Duration timeout = props.naverExchange().tokenTimeout();
    this.http = client(timeout);
    this.profileHttp =
        client(timeout.compareTo(PROFILE_TIMEOUT_MAX) < 0 ? timeout : PROFILE_TIMEOUT_MAX);
  }

  private static RestClient client(Duration readTimeout) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // 시험 WireMock(평문 http)에서 h2c 업그레이드 시도 없이
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * @param tokenBaseUrl 기준 주소(끝 / 없음)
   * @return 네이버 OIDC id_token 과 access token
   */
  public Tokens exchange(String tokenBaseUrl, Request r) {
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
    String accessToken = body.path("access_token").asString("");
    return new Tokens(idToken, accessToken.isBlank() ? null : accessToken);
  }

  /**
   * 네이버 프로필 API({@code GET {profileBaseUrl}/v1/nid/me}, Bearer access token)의 {@code
   * response.profile_image} 원문(2026-11-20 BR-70). 어떤 실패도 예외 없이 null — 교환은 그대로 성공한다. 로그는 짧은 까닭
   * 코드만(토큰·주소 금지). 주소 검사는 호출한 쪽({@code ProviderPictureRules.naver}).
   *
   * @param profileBaseUrl 기준 주소(끝 / 없음)
   */
  /**
   * 네이버 앱 연결 끊기(연동 해제, 2026-11-20 BR-74) — {@code grant_type=delete}. 끊으면 다음 네이버 로그인에서 정보 제공 동의를 다시
   * 받는다. 어떤 실패도 예외 없이 false(교환·로그인은 그대로). access token·secret 은 로그 금지 — 결과 코드만.
   */
  public boolean unlink(
      String tokenBaseUrl, String clientId, String clientSecret, String accessToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "delete");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("access_token", accessToken);
    form.add("service_provider", "NAVER");
    try {
      JsonNode body =
          profileHttp
              .post()
              .uri(tokenBaseUrl + "/oauth2.0/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (body == null || !"success".equals(body.path("result").asString(""))) {
        log.info(
            "네이버 연결 끊기 실패 reason={}",
            body == null ? "empty_response" : safe(body.path("error").asString("no_result")));
        return false;
      }
      return true;
    } catch (HttpStatusCodeException e) {
      log.info("네이버 연결 끊기 실패 reason=http_{}", e.getStatusCode().value());
      return false;
    } catch (RuntimeException e) {
      log.info("네이버 연결 끊기 실패 reason={}", e.getClass().getSimpleName());
      return false;
    }
  }

  public @Nullable String profileImage(String profileBaseUrl, String accessToken) {
    JsonNode body;
    try {
      body =
          profileHttp
              .get()
              .uri(profileBaseUrl + "/v1/nid/me")
              .header("Authorization", "Bearer " + accessToken)
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(JsonNode.class);
    } catch (HttpStatusCodeException e) {
      log.info("네이버 프로필 사진 생략 reason=http_{}", e.getStatusCode().value());
      return null;
    } catch (RuntimeException e) {
      log.info("네이버 프로필 사진 생략 reason={}", e.getClass().getSimpleName());
      return null;
    }
    if (body == null || !body.isObject()) {
      log.info("네이버 프로필 사진 생략 reason=empty_response");
      return null;
    }
    // 네이버는 실패도 200 + resultcode ≠ "00" 으로 줄 수 있다
    String resultCode = body.path("resultcode").asString("");
    if (!resultCode.isEmpty() && !"00".equals(resultCode)) {
      log.info("네이버 프로필 사진 생략 reason=resultcode");
      return null;
    }
    String image = body.path("response").path("profile_image").asString("");
    if (image.isBlank()) {
      log.info("네이버 프로필 사진 생략 reason=missing_profile_image");
      return null;
    }
    return image;
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
