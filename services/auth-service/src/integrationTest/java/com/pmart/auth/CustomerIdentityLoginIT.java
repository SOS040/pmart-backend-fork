package com.pmart.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 휴대폰 확인 단계(ADR-0074, auth 0.6.0 — require-identity 를 시험 속성으로 켠다 — 설정 사본도 2026-11-20 부터 켜짐): 신규 가입
 * → AUTH_IDENTITY(토큰·사건 없음) → identity-verifications(번호) → confirmations: 아직이면 AUTH_IDENTITY·진행 상태,
 * 확인되면 AUTH_MEMBER·토큰·CustomerAuthenticated. 다른 계정의 번호면 409 IDENTITY_ALREADY_REGISTERED(제공자·가입일·가린
 * 이메일)이고 새 계정은 버린다, 탈퇴 막기는 409 IDENTITY_REJOIN_BLOCKED. 확인된 기존 회원은 바로 들어가고, 확인 번호가 없는 기존 회원은 다음 로그인
 * 때 막는다(사용자 (가)). customer-service 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.auth.customer.google.client-ids=web-client.apps.googleusercontent.com",
      "pmart.auth.customer.require-consent=false",
      "pmart.auth.customer.require-identity=true",
      "pmart.it.context=CustomerIdentityLoginIT",
      // 새 공개 경로(설정 저장소에 넣기 전이라 목록 전체를 여기서 — 목록은 통째로 바뀐다)
      "pmart.security.permit-paths[0]=/actuator/health/**",
      "pmart.security.permit-paths[1]=/error",
      "pmart.security.permit-paths[2]=/api/v1/internal/jwks",
      "pmart.security.permit-paths[3]=/api/v1/auth/social-logins",
      "pmart.security.permit-paths[4]=/api/v1/auth/social-logins/*",
      "pmart.security.permit-paths[5]=/api/v1/auth/social-logins/*/completions",
      "pmart.security.permit-paths[6]=/api/v1/auth/social-logins/*/identity-verifications",
      "pmart.security.permit-paths[7]=/api/v1/auth/social-logins/*/identity-verifications/*/confirmations",
      "pmart.security.permit-paths[8]=/api/v1/auth/tokens"
    })
@Import(CustomerSocialLoginIT.FakeGoogle.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "customer",
        baseUrlProperties = "pmart.auth.customer.customer-service-base-url"))
class CustomerIdentityLoginIT extends PostgresKafkaContainerSupport {

  static final String IDENTITY = "/api/v1/internal/accounts/[^/]+/identity";
  static final String START = "/api/v1/internal/accounts/[^/]+/identity-verifications";
  static final String CONFIRM =
      "/api/v1/internal/accounts/[^/]+/identity-verifications/[^/]+/confirmations";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("customer")
  WireMockServer customer;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    customer.resetAll();
    stubIdentity(false);
    customer.stubFor(
        WireMock.post(urlPathMatching(START))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "verificationId", "PV-1",
                        "state", "PENDING",
                        "sendTo", "1666-3538",
                        "text", "123456",
                        "expiresAt", "2099-01-01T00:00:00Z",
                        "phoneMasked", "010-****-0001",
                        "maxAttempts", 3))));
    // 기기 동의 옮기기(회원 토큰 뒤)
    customer.stubFor(
        WireMock.post(urlPathMatching("/api/v1/internal/accounts/[^/]+/device-consent-migrations"))
            .willReturn(json(200, Map.of("migrated", 0))));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(new ObjectMapper().writeValueAsString(body));
  }

  void stubIdentity(boolean verified) {
    customer.stubFor(
        WireMock.get(urlPathMatching(IDENTITY))
            .willReturn(json(200, Map.of("accountId", "x", "verified", verified))));
  }

  void stubConfirm(
      String state,
      @Nullable String outcome,
      @Nullable String existingAccountId,
      @Nullable String rejoinAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("verificationId", "PV-1");
    m.put("state", state);
    m.put("failureReason", null);
    m.put("remainingAttempts", 2);
    m.put("expiresAt", "2099-01-01T00:00:00Z");
    m.put("outcome", outcome);
    m.put("existingAccountId", existingAccountId);
    m.put("existingEmailMasked", existingAccountId == null ? null : "a***@g***");
    m.put("rejoinAvailableAt", rejoinAt);
    customer.stubFor(WireMock.post(urlPathMatching(CONFIRM)).willReturn(json(200, m)));
  }

  record Res(int status, JsonNode body) {}

  Res post(String path, String key, String device, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res login(String device, String token) {
    return post(
        "/auth/social-logins",
        UUID.randomUUID().toString(),
        device,
        Map.of("provider", "GOOGLE", "providerToken", token));
  }

  Res start(String arId, String device, String token) {
    return post(
        "/auth/social-logins/" + arId + "/identity-verifications",
        UUID.randomUUID().toString(),
        device,
        Map.of("providerToken", token, "phone", "010-7777-0001"));
  }

  Res confirm(String arId, String key, String device, String token) {
    return post(
        "/auth/social-logins/" + arId + "/identity-verifications/PV-1/confirmations",
        key,
        device,
        Map.of("providerToken", token));
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void signupNeedsPhoneThenBecomesMember() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    String token = CustomerSocialLoginIT.google(sub);
    Res first = login(device, token);
    assertThat(first.body().path("state").asString()).isEqualTo("AUTH_IDENTITY");
    assertThat(first.body().path("nextAction").asString()).isEqualTo("VERIFY_IDENTITY");
    assertThat(first.body().path("tokens").path("accessToken").isMissingNode()).isTrue();
    String arId = first.body().path("authRequestId").asString();
    String accountId = first.body().path("accountId").asString();
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE type = 'CustomerAuthenticated' AND aggregateid = ?",
                accountId))
        .isZero();

    // 다른 기기·다른 소셜 계정은 못 한다
    assertThat(start(arId, "other-device", token).status()).isEqualTo(403);
    assertThat(
            start(arId, device, CustomerSocialLoginIT.google("g-other-" + UUID.randomUUID()))
                .status())
        .isEqualTo(403);
    Res started = start(arId, device, token);
    assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
    assertThat(started.body().path("text").asString()).isEqualTo("123456");
    assertThat(started.body().path("sendTo").asString()).isEqualTo("1666-3538");
    customer.verify(
        postRequestedFor(urlPathMatching(START))
            .withRequestBody(
                WireMock.matchingJsonPath("$.phone", WireMock.equalTo("010-7777-0001"))));

    // 아직 문자가 없음 → AUTH_IDENTITY·진행 상태
    stubConfirm("PENDING", null, null, null);
    String pendingKey = UUID.randomUUID().toString();
    Res pending = confirm(arId, pendingKey, device, token);
    assertThat(pending.status()).isEqualTo(200);
    // customer 는 변경 요청에 Idempotency-Key 가 없으면 400(2026-11-20 LAN) — 고객이 보낸 키를 넘긴다
    customer.verify(
        postRequestedFor(urlPathMatching(CONFIRM))
            .withHeader("Idempotency-Key", WireMock.equalTo(pendingKey)));
    assertThat(pending.body().path("state").asString()).isEqualTo("AUTH_IDENTITY");
    assertThat(pending.body().path("identityVerification").path("state").asString())
        .isEqualTo("PENDING");
    assertThat(pending.body().path("identityVerification").path("remainingAttempts").asInt())
        .isEqualTo(2);

    // 확인됨 → AUTH_MEMBER·토큰·사건, 같은 키 재전송은 새 토큰
    stubConfirm("VERIFIED", "VERIFIED", null, null);
    String key = UUID.randomUUID().toString();
    Res member = confirm(arId, key, device, token);
    assertThat(member.status()).as(member.body().toString()).isEqualTo(200);
    assertThat(member.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(member.body().path("tokens").path("accessToken").asString()).isNotBlank();
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE type = 'CustomerAuthenticated' AND aggregateid = ?",
                accountId))
        .isEqualTo(1);
    Res again = confirm(arId, key, device, token);
    assertThat(again.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(again.body().path("tokens").path("accessToken").asString())
        .isNotEqualTo(member.body().path("tokens").path("accessToken").asString());
    assertThat(confirm(arId, UUID.randomUUID().toString(), device, token).status())
        .as("다른 키로 이미 끝난 요청")
        .isEqualTo(409);

    // 확인된 회원은 다음 로그인 때 바로 들어간다
    stubIdentity(true);
    Res next = login(device, token);
    assertThat(next.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
  }

  @Test
  void existingUnverifiedMemberIsAskedOnNextLogin() {
    // 스위치 전에 회원이 된 계정(확인 번호 없음) — 사용자 (가): 다음 로그인 때 막고 확인
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    String token = CustomerSocialLoginIT.google(sub);
    String first = login(device, token).body().path("accountId").asString();
    jdbc.update("UPDATE customer_account SET member_since = now() WHERE account_id = ?", first);
    Res r = login(device, token);
    assertThat(r.body().path("state").asString()).isEqualTo("AUTH_IDENTITY");
    assertThat(r.body().path("newAccount").asBoolean()).isFalse();
    assertThat(r.body().path("accountId").asString()).isEqualTo(first);
  }

  @Test
  void phoneOfAnotherAccountShowsThatAccountAndDiscardsTheNewOne() {
    // 기존 계정: 카카오로 가입한 회원(auth 표에 직접)
    String existing = "CU-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO customer_account (account_id, state, created_at, member_since) VALUES (?, 'ACTIVE', now(), '2026-11-01T00:00:00Z')",
        existing);
    jdbc.update(
        "INSERT INTO social_identity (provider, provider_subject, account_id, linked_at) VALUES ('KAKAO', ?, ?, now())",
        "k-" + UUID.randomUUID(),
        existing);

    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    String token = CustomerSocialLoginIT.google(sub);
    Res first = login(device, token);
    String arId = first.body().path("authRequestId").asString();
    String newAccount = first.body().path("accountId").asString();
    start(arId, device, token);
    stubConfirm("FAILED", "IDENTITY_ALREADY_REGISTERED", existing, null);
    Res dup = confirm(arId, UUID.randomUUID().toString(), device, token);
    assertThat(dup.status()).as(dup.body().toString()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("IDENTITY_ALREADY_REGISTERED");
    Map<String, String> details = new LinkedHashMap<>();
    dup.body()
        .path("details")
        .forEach(d -> details.put(d.path("field").asString(), d.path("reason").asString()));
    assertThat(details)
        .containsEntry("provider", "KAKAO")
        .containsEntry("joinedAt", "2026-11-01T00:00:00Z")
        .containsEntry("emailMasked", "a***@g***");
    assertThat(dup.body().toString()).doesNotContain(existing);
    // 새 계정·연결은 버렸다 — 같은 Google 계정의 다음 로그인은 새 가입
    assertThat(count("SELECT count(*) FROM customer_account WHERE account_id = ?", newAccount))
        .isZero();
    Res again = login(device, token);
    assertThat(again.body().path("state").asString()).isEqualTo("AUTH_IDENTITY");
    assertThat(again.body().path("newAccount").asBoolean()).isTrue();
    assertThat(again.body().path("accountId").asString()).isNotEqualTo(newAccount);
    // 끝난 원인증으로는 다시 못 한다
    assertThat(confirm(arId, UUID.randomUUID().toString(), device, token).status()).isEqualTo(409);

    // 탈퇴 막기
    String arId2 = again.body().path("authRequestId").asString();
    start(arId2, device, token);
    stubConfirm("FAILED", "IDENTITY_REJOIN_BLOCKED", null, "2026-12-20T00:00:00Z");
    Res blocked = confirm(arId2, UUID.randomUUID().toString(), device, token);
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.body().path("code").asString()).isEqualTo("IDENTITY_REJOIN_BLOCKED");
    assertThat(blocked.body().path("details").path(0).path("field").asString())
        .isEqualTo("rejoinAvailableAt");
  }
}
