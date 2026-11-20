package com.pmart.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 네이버 인가 코드 → ID 토큰 서버 교환(ADR-0078, auth 0.10.0 exchangeNaverCode). 네이버 토큰 엔드포인트는 WireMock. 성공·같은 키
 * 재전송(네이버 한 번)·거절 401·장애/시간 초과 503·redirectUri 불일치 400. 공개 경로는 설정 사본 permit-paths. Client Secret
 * 미설정 422 는 {@code NaverCodeExchangeServiceTest}(설정값으로).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.it.context=NaverCodeExchangeIT",
      "pmart.auth.customer.naver.client-ids=naver-client-id,second-id",
      "pmart.auth.customer.naver.client-secret=test-naver-secret",
      "pmart.auth.customer.naver.callback-url=" + NaverCodeExchangeIT.CALLBACK,
      "pmart.auth.customer.naver.token-timeout=PT1S"
    })
@EnableWireMock(
    @ConfigureWireMock(
        name = "naver",
        baseUrlProperties = "pmart.auth.customer.naver.token-base-url"))
class NaverCodeExchangeIT extends PostgresKafkaContainerSupport {

  static final String CALLBACK =
      "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer";
  static final String TOKEN = "/oauth2/token";
  static final String VERIFIER = "a".repeat(43);

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("naver")
  WireMockServer naver;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    naver.resetAll();
  }

  record Res(int status, JsonNode body) {}

  Res exchange(String key, String code, String redirectUri) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("authorizationCode", code);
    body.put("codeVerifier", VERIFIER);
    body.put("redirectUri", redirectUri);
    body.put("state", "st-1");
    var e =
        http.post()
            .uri("/auth/naver-token-exchanges")
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void stub(int status, Object body) {
    naver.stubFor(
        WireMock.post(urlPathEqualTo(TOKEN))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(new ObjectMapper().writeValueAsString(body))));
  }

  @Test
  void exchangesCodeReturnsOnlyIdTokenAndReplaysSameKeyWithoutCallingNaverAgain() {
    stub(
        200,
        Map.of(
            "access_token", "naver-access",
            "refresh_token", "naver-refresh",
            "token_type", "bearer",
            "expires_in", 3600,
            "id_token", "naver.id.token"));
    String key = UUID.randomUUID().toString();
    String code = "code-" + UUID.randomUUID();

    Res first = exchange(key, code, CALLBACK);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("idToken").asString()).isEqualTo("naver.id.token");
    assertThat(first.body().has("accessToken")).isFalse();
    assertThat(first.body().toString()).doesNotContain("naver-access").doesNotContain("refresh");

    // client_secret_post — 설정의 첫 Client ID·Secret, 앱의 코드·verifier·redirect_uri·state
    naver.verify(
        1,
        postRequestedFor(urlPathEqualTo(TOKEN))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withFormParam("grant_type", equalTo("authorization_code"))
            .withFormParam("client_id", equalTo("naver-client-id"))
            .withFormParam("client_secret", equalTo("test-naver-secret"))
            .withFormParam("code", equalTo(code))
            .withFormParam("code_verifier", equalTo(VERIFIER))
            .withFormParam("redirect_uri", equalTo(CALLBACK))
            .withFormParam("state", equalTo("st-1")));

    // 응답 유실 재전송: 같은 결과, 네이버는 다시 부르지 않는다
    Res again = exchange(key, code, CALLBACK);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("idToken").asString()).isEqualTo("naver.id.token");
    naver.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));

    // 같은 키·다른 코드는 409
    assertThat(exchange(key, "other-code", CALLBACK).status()).isEqualTo(409);
    naver.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
  }

  @Test
  void naverRejectionIs401() {
    // 네이버는 거절을 200 + error 로도, 4xx 로도 준다
    stub(200, Map.of("error", "invalid_grant", "error_description", "no valid data"));
    Res r = exchange(UUID.randomUUID().toString(), "used-code", CALLBACK);
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");

    naver.resetAll();
    stub(401, Map.of("error", "invalid_client"));
    Res r2 = exchange(UUID.randomUUID().toString(), "code", CALLBACK);
    assertThat(r2.status()).isEqualTo(401);
    assertThat(r2.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");
  }

  @Test
  void naverFailureOrTimeoutIs503AndSameKeyCanRetry() {
    stub(500, Map.of("error", "server_error"));
    String key = UUID.randomUUID().toString();
    Res r = exchange(key, "code-503", CALLBACK);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.body().path("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");

    // 응답 시간 초과(token-timeout 1초)
    naver.resetAll();
    naver.stubFor(
        WireMock.post(urlPathEqualTo(TOKEN))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id_token\":\"late\"}")
                    .withFixedDelay(2500)));
    Res timeout = exchange(UUID.randomUUID().toString(), "code-timeout", CALLBACK);
    assertThat(timeout.status()).isEqualTo(503);
    assertThat(timeout.body().path("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");

    // 실패는 기록되지 않아 같은 키로 다시 보내면 다시 교환한다
    naver.resetAll();
    stub(200, Map.of("id_token", "after-retry"));
    Res retry = exchange(key, "code-503", CALLBACK);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body().path("idToken").asString()).isEqualTo("after-retry");
  }

  @Test
  void wrongRedirectUriIs400WithoutCallingNaver() {
    stub(200, Map.of("id_token", "x"));
    Res r =
        exchange(
            UUID.randomUUID().toString(),
            "code",
            "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/rider");
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
    naver.verify(0, postRequestedFor(urlPathEqualTo(TOKEN)));
  }
}
