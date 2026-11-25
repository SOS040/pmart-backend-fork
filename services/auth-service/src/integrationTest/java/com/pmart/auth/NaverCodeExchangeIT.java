package com.pmart.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
 * 미설정 422 는 {@code NaverCodeExchangeServiceTest}(설정값으로). 프로필 사진(0.10.1 BR-70): 같은 WireMock 의 {@code
 * /v1/nid/me} — pstatic https 면 profileImageUrl, 실패·다른 호스트·시간 초과면 빠지고 교환은 200.
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
        baseUrlProperties = {
          "pmart.auth.customer.naver.token-base-url",
          "pmart.auth.customer.naver.profile-base-url"
        }))
class NaverCodeExchangeIT extends PostgresKafkaContainerSupport {

  static final String CALLBACK =
      "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer";
  static final String TOKEN = "/oauth2/token";
  static final String PROFILE = "/v1/nid/me";
  static final String PICTURE = "https://phinf.pstatic.net/contact/20261120_1/profile.jpg";
  static final String VERIFIER = "a".repeat(43);

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired com.pmart.auth.infrastructure.NaverProfileEmailStore naverEmails;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
    return exchange(key, code, redirectUri, null);
  }

  Res exchange(String key, String code, String redirectUri, String purpose) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (purpose != null) {
      body.put("purpose", purpose);
    }
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

  void stubProfile(int status, Object body) {
    naver.stubFor(
        WireMock.get(urlPathEqualTo(PROFILE))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(new ObjectMapper().writeValueAsString(body))));
  }

  /** 사진 없음 — 필드가 없거나 null. */
  static boolean noPicture(Res r) {
    JsonNode p = r.body().path("profileImageUrl");
    return p.isMissingNode() || p.isNull();
  }

  static Map<String, Object> profileBody(String image) {
    return Map.of(
        "resultcode",
        "00",
        "message",
        "success",
        "response",
        Map.of("id", "n-1", "profile_image", image));
  }

  void stubTokens() {
    stub(
        200,
        Map.of(
            "access_token", "naver-access",
            "refresh_token", "naver-refresh",
            "token_type", "bearer",
            "expires_in", 3600,
            "id_token", "naver.id.token"));
  }

  /**
   * BR-74 (C): 탈퇴 재인증(WITHDRAWAL)은 프로필을 읽은 뒤 네이버 연결을 끊고, 로그인(기본)은 끊지 않는다(LAN 스위치 꺼짐 — 이 id_token 은
   * sub 를 읽을 수 없는 모양이라 BR-79 가입 전 끊기도 하지 않는다). 끊기 실패도 교환 200.
   */
  @Test
  void withdrawalPurposeUnlinksNaverAfterExchangeButLoginDoesNot() {
    stubTokens();
    stubProfile(200, profileBody(PICTURE));
    naver.stubFor(
        WireMock.post(urlPathEqualTo("/oauth2.0/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"naver-access\",\"result\":\"success\"}")));

    Res login = exchange(java.util.UUID.randomUUID().toString(), "code-login", CALLBACK);
    assertThat(login.status()).isEqualTo(200);
    naver.verify(0, WireMock.postRequestedFor(urlPathEqualTo("/oauth2.0/token")));

    Res withdrawal =
        exchange(java.util.UUID.randomUUID().toString(), "code-wd", CALLBACK, "WITHDRAWAL");
    assertThat(withdrawal.status()).isEqualTo(200);
    assertThat(withdrawal.body().path("idToken").asString()).isEqualTo("naver.id.token");
    naver.verify(
        1,
        WireMock.postRequestedFor(urlPathEqualTo("/oauth2.0/token"))
            .withRequestBody(WireMock.containing("grant_type=delete"))
            .withRequestBody(WireMock.containing("access_token=naver-access"))
            .withRequestBody(WireMock.containing("service_provider=NAVER")));

    // 끊기 실패해도 교환은 성공
    naver.stubFor(
        WireMock.post(urlPathEqualTo("/oauth2.0/token")).willReturn(aResponse().withStatus(500)));
    assertThat(
            exchange(java.util.UUID.randomUUID().toString(), "code-wd2", CALLBACK, "WITHDRAWAL")
                .status())
        .isEqualTo(200);
  }

  @Autowired com.pmart.auth.infrastructure.CustomerStore customers;

  /** 서명 없는 id_token 모양(교환 응답은 네이버가 직접 준 것이라 서버가 sub 만 읽는다). */
  static String idTokenWithSub(String sub) {
    java.util.Base64.Encoder b = java.util.Base64.getUrlEncoder().withoutPadding();
    return b.encodeToString("{\"alg\":\"RS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "."
        + b.encodeToString(
            ("{\"sub\":\"" + sub + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + ".sig";
  }

  int unlinkCalls() {
    return naver
        .findAll(
            WireMock.postRequestedFor(urlPathEqualTo("/oauth2.0/token"))
                .withRequestBody(WireMock.containing("grant_type=delete")))
        .size();
  }

  /**
   * 2026-11-25(BR-79 가입 전 끊기 없앰): 로그인 교환은 처음·가입 중·회원 모두 네이버 연결을 끊지 않는다(가입 뒤 첫 로그인·탈퇴 재인증에 동의 화면이 또
   * 뜨던 것). 끊기는 탈퇴 재인증(WITHDRAWAL)만.
   */
  @Test
  void unlinksNaverWhenSignupNotFinishedButNotForMembers() {
    stubProfile(200, profileBody(PICTURE));
    naver.stubFor(
        WireMock.post(urlPathEqualTo("/oauth2.0/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"naver-access\",\"result\":\"success\"}")));
    java.time.Instant now = java.time.Instant.now();
    String fresh = "naver-new-" + UUID.randomUUID();
    String pending = "naver-pending-" + UUID.randomUUID();
    String member = "naver-member-" + UUID.randomUUID();
    String pendingAccount = "CU-" + UUID.randomUUID();
    customers.insertAccount(pendingAccount, now);
    customers.insertIdentity("NAVER", pending, pendingAccount, now);
    String memberAccount = "CU-" + UUID.randomUUID();
    customers.insertAccount(memberAccount, now);
    customers.insertIdentity("NAVER", member, memberAccount, now);
    customers.markMember(memberAccount, now);

    int before = unlinkCalls();
    for (String sub : new String[] {fresh, pending}) {
      stub(
          200,
          Map.of(
              "access_token",
              "naver-access",
              "token_type",
              "bearer",
              "id_token",
              idTokenWithSub(sub)));
      assertThat(exchange(UUID.randomUUID().toString(), "code-" + sub, CALLBACK).status())
          .isEqualTo(200);
    }
    assertThat(unlinkCalls() - before).isZero();

    stub(
        200,
        Map.of(
            "access_token",
            "naver-access",
            "token_type",
            "bearer",
            "id_token",
            idTokenWithSub(member)));
    assertThat(exchange(UUID.randomUUID().toString(), "code-member", CALLBACK).status())
        .isEqualTo(200);
    assertThat(unlinkCalls() - before).isZero();

    // 끊기 실패해도 교환은 성공
    naver.stubFor(
        WireMock.post(urlPathEqualTo("/oauth2.0/token")).willReturn(aResponse().withStatus(500)));
    stub(
        200,
        Map.of(
            "access_token",
            "naver-access",
            "token_type",
            "bearer",
            "id_token",
            idTokenWithSub(fresh)));
    assertThat(exchange(UUID.randomUUID().toString(), "code-fresh-2", CALLBACK).status())
        .isEqualTo(200);
  }

  @Test
  void exchangesCodeReturnsOnlyIdTokenAndReplaysSameKeyWithoutCallingNaverAgain() {
    stubProfile(200, profileBody(PICTURE));
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
    // 프로필 사진(BR-70): access token 으로 프로필 API 한 번
    assertThat(first.body().path("profileImageUrl").asString()).isEqualTo(PICTURE);
    naver.verify(
        1,
        getRequestedFor(urlPathEqualTo(PROFILE))
            .withHeader("Authorization", equalTo("Bearer naver-access")));

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
    assertThat(again.body().path("profileImageUrl").asString()).isEqualTo(PICTURE);
    naver.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    naver.verify(1, getRequestedFor(urlPathEqualTo(PROFILE)));

    // 같은 키·다른 코드는 409
    assertThat(exchange(key, "other-code", CALLBACK).status()).isEqualTo(409);
    naver.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
  }

  @Test
  void profileFailureOrForeignHostOmitsPictureButExchangeSucceeds() {
    // 프로필 API 401 → 사진 없음, 교환은 200
    stubTokens();
    stubProfile(401, Map.of("resultcode", "024", "message", "Authentication failed"));
    String key = UUID.randomUUID().toString();
    Res r = exchange(key, "code-p1", CALLBACK);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("idToken").asString()).isEqualTo("naver.id.token");
    assertThat(noPicture(r)).isTrue();
    // 재전송은 저장된 결과 그대로(사진 없음) — 네이버·프로필 다시 안 부름
    naver.resetRequests();
    stubProfile(200, profileBody(PICTURE));
    Res again = exchange(key, "code-p1", CALLBACK);
    assertThat(again.status()).isEqualTo(200);
    assertThat(noPicture(again)).isTrue();
    naver.verify(0, postRequestedFor(urlPathEqualTo(TOKEN)));
    naver.verify(0, getRequestedFor(urlPathEqualTo(PROFILE)));

    // pstatic 이 아닌 주소·http 는 버린다
    for (String bad :
        new String[] {"https://evil.example/p.jpg", "http://phinf.pstatic.net/p.jpg"}) {
      naver.resetAll();
      stubTokens();
      stubProfile(200, profileBody(bad));
      Res b = exchange(UUID.randomUUID().toString(), "code-bad", CALLBACK);
      assertThat(b.status()).isEqualTo(200);
      assertThat(noPicture(b)).isTrue();
    }

    // profile_image 없음·200 + resultcode 오류
    naver.resetAll();
    stubTokens();
    stubProfile(
        200, Map.of("resultcode", "00", "message", "success", "response", Map.of("id", "n-1")));
    assertThat(noPicture(exchange(UUID.randomUUID().toString(), "c-none", CALLBACK))).isTrue();
    naver.resetAll();
    stubTokens();
    stubProfile(
        200,
        Map.of(
            "resultcode", "024", "message", "fail", "response", Map.of("profile_image", PICTURE)));
    assertThat(noPicture(exchange(UUID.randomUUID().toString(), "c-rc", CALLBACK))).isTrue();

    // 프로필 시간 초과(token-timeout 1초 → 프로필도 1초) — 교환은 200
    naver.resetAll();
    stubTokens();
    naver.stubFor(
        WireMock.get(urlPathEqualTo(PROFILE))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(new ObjectMapper().writeValueAsString(profileBody(PICTURE)))
                    .withFixedDelay(2000)));
    Res slow = exchange(UUID.randomUUID().toString(), "c-slow", CALLBACK);
    assertThat(slow.status()).isEqualTo(200);
    assertThat(slow.body().path("idToken").asString()).isEqualTo("naver.id.token");
    assertThat(noPicture(slow)).isTrue();

    // access_token 없는 응답은 프로필을 부르지 않는다
    naver.resetAll();
    stub(200, Map.of("id_token", "only-id"));
    stubProfile(200, profileBody(PICTURE));
    Res noAccess = exchange(UUID.randomUUID().toString(), "c-noaccess", CALLBACK);
    assertThat(noAccess.status()).isEqualTo(200);
    assertThat(noPicture(noAccess)).isTrue();
    naver.verify(0, getRequestedFor(urlPathEqualTo(PROFILE)));
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

  /**
   * BR-77: 교환 때 프로필의 이메일을 id_token sub 에 묶어 두고(응답에는 싣지 않음), 로그인 때 그 sub 로 한 번 꺼낸다. 이메일이 없거나 프로필이
   * 실패하면 묶지 않고 교환은 그대로 200.
   */
  @Test
  void profileEmailIsHeldForTheIdTokenSubjectNotReturnedToApp() {
    String sub = "naver-sub-" + UUID.randomUUID();
    var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
    String idToken =
        b64.encodeToString("{\"alg\":\"RS256\"}".getBytes())
            + "."
            + b64.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes())
            + ".sig";
    stub(200, Map.of("access_token", "naver-access", "id_token", idToken));
    stubProfile(
        200,
        Map.of(
            "resultcode",
            "00",
            "message",
            "success",
            "response",
            Map.of("id", "n-1", "profile_image", PICTURE, "email", "naver.user@naver.com")));
    Res r = exchange(UUID.randomUUID().toString(), "code-email", CALLBACK);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().toString()).doesNotContain("naver.user@naver.com");
    assertThat(r.body().path("profileImageUrl").asString()).isEqualTo(PICTURE);
    // 원래 sub 는 남기지 않는다
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM naver_profile_email WHERE subject_hash = ?",
                Integer.class,
                com.pmart.auth.infrastructure.NaverProfileEmailStore.hash(sub)))
        .isEqualTo(1);
    java.time.Instant now = java.time.Instant.now();
    assertThat(naverEmails.take(sub, now)).isEqualTo("naver.user@naver.com");
    assertThat(naverEmails.take(sub, now)).isNull(); // 한 번만

    // 이메일 없음 → 묶지 않음
    naver.resetAll();
    String sub2 = "naver-sub-" + UUID.randomUUID();
    stub(
        200,
        Map.of(
            "access_token",
            "naver-access",
            "id_token",
            "h." + b64.encodeToString(("{\"sub\":\"" + sub2 + "\"}").getBytes()) + ".s"));
    stubProfile(200, profileBody(PICTURE));
    assertThat(exchange(UUID.randomUUID().toString(), "code-noemail", CALLBACK).status())
        .isEqualTo(200);
    assertThat(naverEmails.take(sub2, now)).isNull();

    // 만료가 지난 값은 주지 않는다
    naverEmails.save(sub2, "old@naver.com", now.minusSeconds(1));
    assertThat(naverEmails.take(sub2, now)).isNull();
  }

  /**
   * 2026-11-25: 프로필 이름(실명 name 만)·별명(nickname, 없으면 이름)도 이메일과 함께 묶는다 — 이메일이 없어도 이름만으로. 응답에는 싣지 않는다.
   */
  @Test
  void profileNameIsHeldWithOrWithoutEmail() {
    var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
    java.time.Instant now = java.time.Instant.now();
    String sub = "naver-sub-" + UUID.randomUUID();
    stub(
        200,
        Map.of(
            "access_token",
            "naver-access",
            "id_token",
            "h." + b64.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes()) + ".s"));
    stubProfile(
        200,
        Map.of(
            "resultcode",
            "00",
            "response",
            Map.of("id", "n-1", "name", "홍길동", "nickname", "길동", "email", "gd@naver.com")));
    Res r = exchange(UUID.randomUUID().toString(), "code-name", CALLBACK);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().toString()).doesNotContain("홍길동");
    var held = naverEmails.takeProfile(sub, now);
    assertThat(held.email()).isEqualTo("gd@naver.com");
    assertThat(held.name()).isEqualTo("홍길동");
    assertThat(held.nickname()).isEqualTo("길동");

    // 이름 없이 별명만, 이메일 없음 → 별명만 묶는다(2026-11-25: 이름 칸은 비움 — 고객앱이 입력받는다)
    naver.resetAll();
    String sub2 = "naver-sub-" + UUID.randomUUID();
    stub(
        200,
        Map.of(
            "access_token",
            "naver-access",
            "id_token",
            "h." + b64.encodeToString(("{\"sub\":\"" + sub2 + "\"}").getBytes()) + ".s"));
    stubProfile(200, Map.of("resultcode", "00", "response", Map.of("id", "n-2", "nickname", "길동")));
    assertThat(exchange(UUID.randomUUID().toString(), "code-nick", CALLBACK).status())
        .isEqualTo(200);
    var nick = naverEmails.takeProfile(sub2, now);
    assertThat(nick.email()).isNull();
    assertThat(nick.name()).isNull();
    assertThat(nick.nickname()).isEqualTo("길동");
  }
}
