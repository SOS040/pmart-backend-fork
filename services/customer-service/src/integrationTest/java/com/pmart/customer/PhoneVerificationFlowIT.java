package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.Map;
import java.util.UUID;
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
 * 휴대폰 번호 확인(0.9.0, 2026-11-19 — OCTOMO 는 WireMock): 시작 → 아직 안 옴(PENDING, 남은 횟수) → 옴(VERIFIED, 프로필
 * phoneVerified) → 다시 확인은 OCTOMO 를 부르지 않음, 번호를 바꾸면 미확인, 같은 키 재시도는 같은 본문, 번호 없음·010 아님 400, 횟수 다 쓰면
 * FAILED, OCTOMO 키 오류는 503(시도로 세지 않음).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.customer.phone-verification.api-key=test-octomo-key",
      "pmart.customer.phone-verification.max-attempts=3",
      // 한 번호 한 계정(ADR-0074) — 설정 저장소에 넣기 전이라 여기서
      "pmart.customer.identity.rejoin-block=P30D"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "octomo",
        baseUrlProperties = "pmart.customer.phone-verification.octomo-base-url"))
class PhoneVerificationFlowIT extends PostgresKafkaContainerSupport {

  static final String EXISTS = "/octomo/v1/public/message/exists";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("octomo")
  WireMockServer octomo;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    octomo.resetAll();
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body == null ? Map.of() : body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  String customerWithPhone(String phone) {
    String token = TestJwt.customer("CU-PV-" + UUID.randomUUID());
    Res r =
        post(token, "/me/profile-changes", UUID.randomUUID().toString(), Map.of("phone", phone));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return token;
  }

  void octomoSays(boolean exists) {
    octomo.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(EXISTS))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"exists\":" + exists + "}")));
  }

  Res confirm(String token, String id) {
    return post(
        token,
        "/me/phone-verifications/" + id + "/confirmations",
        UUID.randomUUID().toString(),
        null);
  }

  @Test
  void startConfirmVerifiesPhone() {
    String token = customerWithPhone("010-1234-5678");
    String key = UUID.randomUUID().toString();
    Res start = post(token, "/me/phone-verifications", key, null);
    assertThat(start.status()).as(start.body().toString()).isEqualTo(201);
    String id = start.body().path("verificationId").asString();
    String text = start.body().path("text").asString();
    assertThat(text).matches("\\d{6}");
    assertThat(start.body().path("sendTo").asString()).isEqualTo("1666-3538");
    assertThat(start.body().path("phoneMasked").asString()).doesNotContain("1234-5678");
    // 같은 키 재시도 = 같은 확인·같은 본문
    Res again = post(token, "/me/phone-verifications", key, null);
    assertThat(again.body().path("verificationId").asString()).isEqualTo(id);
    assertThat(again.body().path("text").asString()).isEqualTo(text);

    octomoSays(false);
    Res miss = confirm(token, id);
    assertThat(miss.status()).isEqualTo(200);
    assertThat(miss.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(miss.body().path("remainingAttempts").asInt()).isEqualTo(2);
    octomo.verify(
        postRequestedFor(urlPathEqualTo(EXISTS))
            .withHeader("Authorization", equalTo("Octomo test-octomo-key"))
            .withRequestBody(
                equalToJson(
                    "{\"mobileNum\":\"01012345678\",\"text\":\""
                        + text
                        + "\",\"withinMinutes\":10}")));

    octomoSays(true);
    Res ok = confirm(token, id);
    assertThat(ok.body().path("state").asString()).isEqualTo("VERIFIED");
    assertThat(ok.body().path("verifiedAt").isNull()).isFalse();
    assertThat(get(token, "/me").body().path("phoneVerified").asBoolean()).isTrue();
    // 끝난 확인은 OCTOMO 를 다시 부르지 않는다
    int calls = octomo.getAllServeEvents().size();
    assertThat(confirm(token, id).body().path("state").asString()).isEqualTo("VERIFIED");
    assertThat(octomo.getAllServeEvents()).hasSize(calls);
    // 확인된 번호는 다시 시작하지 않는다
    assertThat(post(token, "/me/phone-verifications", UUID.randomUUID().toString(), null).status())
        .isEqualTo(409);
    // 번호를 바꾸면 미확인
    post(
        token, "/me/profile-changes", UUID.randomUUID().toString(), Map.of("phone", "01098765432"));
    assertThat(get(token, "/me").body().path("phoneVerified").asBoolean()).isFalse();
  }

  @Test
  void rejectsMissingOrUnsupportedPhoneAndCountsAttempts() {
    String noPhone = TestJwt.customer("CU-PV-" + UUID.randomUUID());
    get(noPhone, "/me");
    Res none = post(noPhone, "/me/phone-verifications", UUID.randomUUID().toString(), null);
    assertThat(none.status()).isEqualTo(400);
    assertThat(none.body().path("code").asString()).isEqualTo("PHONE_REQUIRED");

    String old = customerWithPhone("0111234567");
    Res unsupported = post(old, "/me/phone-verifications", UUID.randomUUID().toString(), null);
    assertThat(unsupported.status()).isEqualTo(400);
    assertThat(unsupported.body().path("code").asString()).isEqualTo("PHONE_NOT_SUPPORTED");

    String token = customerWithPhone("01055556666");
    String id =
        post(token, "/me/phone-verifications", UUID.randomUUID().toString(), null)
            .body()
            .path("verificationId")
            .asString();
    // OCTOMO 키 오류 — 503, 시도로 세지 않는다
    octomo.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(EXISTS))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"statusCode\":401,\"message\":\"Invalid API Key\"}")));
    assertThat(confirm(token, id).status()).isEqualTo(503);
    assertThat(get(token, "/me/phone-verifications/" + id).body().path("remainingAttempts").asInt())
        .isEqualTo(3);

    octomo.resetAll();
    octomoSays(false);
    confirm(token, id);
    confirm(token, id);
    Res last = confirm(token, id);
    assertThat(last.body().path("state").asString()).isEqualTo("FAILED");
    assertThat(last.body().path("failureReason").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
    assertThat(last.body().path("remainingAttempts").asInt()).isZero();
    // 다른 고객은 볼 수 없다
    assertThat(get(old, "/me/phone-verifications/" + id).status()).isEqualTo(404);
  }

  // ---------------------------------------------------------------- 가입 단계 확인(0.10.0, ADR-0074)

  Res internalPost(String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "auth-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body == null ? Map.of() : body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res internalGet(String path) {
    var e = http.get().uri(path).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 가입 단계 확인 하나를 끝까지(문자 옴) — 확인 결과. */
  Res signupVerify(String accountId, String phone) {
    Res start =
        internalPost(
            "/internal/accounts/" + accountId + "/identity-verifications",
            UUID.randomUUID().toString(),
            Map.of("phone", phone));
    assertThat(start.status()).as(start.body().toString()).isEqualTo(201);
    octomoSays(true);
    return internalPost(
        "/internal/accounts/"
            + accountId
            + "/identity-verifications/"
            + start.body().path("verificationId").asString()
            + "/confirmations",
        UUID.randomUUID().toString(),
        null);
  }

  @Test
  void signupIdentityOneNumberOneAccountAndRejoinBlock() {
    // 가입 전 계정(프로필 없음) — 번호를 본문으로, 문자가 오면 VERIFIED·계정 확인 번호·프로필 phoneVerified
    String first = "CU-ID-" + UUID.randomUUID();
    assertThat(
            internalGet("/internal/accounts/" + first + "/identity")
                .body()
                .path("verified")
                .asBoolean())
        .isFalse();
    Res ok = signupVerify(first, "010-7777-0001");
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("VERIFIED");
    assertThat(ok.body().path("outcome").asString()).isEqualTo("VERIFIED");
    JsonNode identity = internalGet("/internal/accounts/" + first + "/identity").body();
    assertThat(identity.path("verified").asBoolean())
        .as(
            identity
                + " rows="
                + jdbc.queryForList(
                    "SELECT account_id, released_at FROM customer_identity WHERE account_id = ?",
                    first))
        .isTrue();
    JsonNode me = get(TestJwt.customer(first), "/me").body();
    assertThat(me.path("phoneVerified").asBoolean()).isTrue();
    // 이미 확인된 계정은 다시 시작 못 함
    assertThat(
            internalPost(
                    "/internal/accounts/" + first + "/identity-verifications",
                    UUID.randomUUID().toString(),
                    Map.of("phone", "01077770001"))
                .status())
        .isEqualTo(409);

    // 같은 번호의 다른 계정 → 문자가 확인돼도 FAILED·IDENTITY_ALREADY_REGISTERED(그 계정 ID)
    String second = "CU-ID-" + UUID.randomUUID();
    Res dup = signupVerify(second, "01077770001");
    assertThat(dup.body().path("state").asString()).isEqualTo("FAILED");
    assertThat(dup.body().path("outcome").asString()).isEqualTo("IDENTITY_ALREADY_REGISTERED");
    assertThat(dup.body().path("existingAccountId").asString()).isEqualTo(first);
    assertThat(
            internalGet("/internal/accounts/" + second + "/identity")
                .body()
                .path("verified")
                .asBoolean())
        .isFalse();

    // /me 확인도 같은 규칙 — 다른 계정의 번호면 PHONE_ALREADY_REGISTERED(안내 없음)
    String token = customerWithPhone("01077770001");
    Res start = post(token, "/me/phone-verifications", UUID.randomUUID().toString(), null);
    octomoSays(true);
    Res me2 = confirm(token, start.body().path("verificationId").asString());
    assertThat(me2.body().path("state").asString()).isEqualTo("FAILED");
    assertThat(me2.body().path("failureReason").asString()).isEqualTo("PHONE_ALREADY_REGISTERED");

    // 탈퇴로 풀린 지 30일 안 → IDENTITY_REJOIN_BLOCKED(다시 가입할 수 있는 때)
    java.time.Instant released = java.time.Instant.now().minus(java.time.Duration.ofDays(10));
    jdbc.update(
        "UPDATE customer_identity SET released_at = ? WHERE account_id = ?",
        java.sql.Timestamp.from(released),
        first);
    String third = "CU-ID-" + UUID.randomUUID();
    Res blocked = signupVerify(third, "01077770001");
    assertThat(blocked.body().path("outcome").asString()).isEqualTo("IDENTITY_REJOIN_BLOCKED");
    assertThat(
            java.time.OffsetDateTime.parse(blocked.body().path("rejoinAvailableAt").asString())
                .toInstant())
        .isCloseTo(
            released.plus(java.time.Duration.ofDays(30)),
            within(1, java.time.temporal.ChronoUnit.SECONDS));
    // 기간이 지나면 다시 확인할 수 있다
    jdbc.update(
        "UPDATE customer_identity SET released_at = ? WHERE account_id = ?",
        java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(31))),
        first);
    Res later = signupVerify("CU-ID-" + UUID.randomUUID(), "01077770001");
    assertThat(later.body().path("outcome").asString()).isEqualTo("VERIFIED");
  }
}
