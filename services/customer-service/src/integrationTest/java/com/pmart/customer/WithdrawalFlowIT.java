package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
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
 * 고객 탈퇴(0.12.0, ADR-0077 — auth·order·claim 은 WireMock): 재인증 거절·다른 계정 401·403, 접수 → 의무 있으면 대기(신규 구매
 * 막힘·주문 조회는 그대로) → 철회 → 다시 접수 → 의무 끝나면 완료(개인정보·주소록 지움·확인 번호 풀림·사건). 의무 조회 실패는 UNKNOWN(완료하지 않음).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.customer.withdrawal.job-tick=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "auth", baseUrlProperties = "pmart.customer.withdrawal.auth-base-url"),
  @ConfigureWireMock(
      name = "order",
      baseUrlProperties = "pmart.customer.withdrawal.order-base-url"),
  @ConfigureWireMock(name = "claim", baseUrlProperties = "pmart.customer.withdrawal.claim-base-url")
})
class WithdrawalFlowIT extends PostgresKafkaContainerSupport {

  static final String REAUTH = "/api/v1/internal/customers/[^/]+/reauthentications";
  static final String ELIGIBILITY = "/api/v1/internal/accounts/[^/]+/withdrawal-eligibility";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("auth")
  WireMockServer auth;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("claim")
  WireMockServer claim;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    auth.resetAll();
    order.resetAll();
    claim.resetAll();
    auth.stubFor(
        post(urlPathMatching(REAUTH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"verified\":true,\"provider\":\"KAKAO\"}")));
  }

  record Res(int status, JsonNode body) {}

  Res call(String method, String token, String path, Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void eligibility(WireMockServer s, boolean eligible, String kind, String ref) {
    s.resetAll();
    String list = eligible ? "[]" : "[{\"kind\":\"" + kind + "\",\"ref\":\"" + ref + "\"}]";
    s.stubFor(
        get(urlPathMatching(ELIGIBILITY))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"eligible\":" + eligible + ",\"openObligations\":" + list + "}")));
  }

  static final Map<String, Object> REAUTH_BODY =
      Map.of("provider", "KAKAO", "providerToken", "id-token", "reasonCode", "NOT_USED");

  String status(String id) {
    return jdbc.queryForObject(
        "SELECT status FROM customer WHERE account_id = ?", String.class, id);
  }

  @Test
  void requestBlockRevokeAndComplete() {
    String id = "CU-WD-" + UUID.randomUUID();
    String token = TestJwt.customer(id);
    assertThat(call("GET", token, "/me", null).status()).isEqualTo(200);
    jdbc.update(
        "UPDATE customer SET name = '김탈퇴', phone = '01011112222', email = 'w@example.com', nickname = '별명'"
            + " WHERE account_id = ?",
        id);
    jdbc.update(
        "INSERT INTO customer_identity (account_id, phone_hash, phone, verified_at) VALUES (?, ?, ?, ?)",
        id,
        "a".repeat(64),
        "01011112222",
        java.sql.Timestamp.from(Instant.now()));
    assertThat(
            call(
                    "POST",
                    token,
                    "/me/addresses",
                    Map.of("roadAddress", "서울 강남구 테헤란로 1", "detailAddress", "1층"))
                .status())
        .isIn(200, 201);

    // 재인증 거절·다른 계정 소셜
    auth.resetAll();
    auth.stubFor(post(urlPathMatching(REAUTH)).willReturn(aResponse().withStatus(401)));
    Res rejected = call("POST", token, "/me/withdrawals", REAUTH_BODY);
    assertThat(rejected.status()).isEqualTo(401);
    assertThat(rejected.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");
    auth.resetAll();
    auth.stubFor(post(urlPathMatching(REAUTH)).willReturn(aResponse().withStatus(403)));
    assertThat(call("POST", token, "/me/withdrawals", REAUTH_BODY).body().path("code").asString())
        .isEqualTo("REAUTHENTICATION_MISMATCH");
    assertThat(status(id)).isEqualTo("ACTIVE");
    setUp();

    // 진행 중 주문 → 접수는 되고 대기(신규 구매 막힘)
    eligibility(order, false, "ORDER_IN_PROGRESS", "ORD-1");
    eligibility(claim, true, null, null);
    Res accepted = call("POST", token, "/me/withdrawals", REAUTH_BODY);
    assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(202);
    String wid = accepted.body().path("withdrawalId").asString();
    assertThat(accepted.body().path("state").asString()).isEqualTo("BLOCKED_BY_OBLIGATIONS");
    assertThat(accepted.body().at("/openObligations/0/kind").asString())
        .isEqualTo("ORDER_IN_PROGRESS");
    assertThat(status(id)).isEqualTo("WITHDRAW_PENDING");
    JsonNode internal = call("GET", token, "/internal/accounts/" + id + "/status", null).body();
    assertThat(internal.path("purchasable").asBoolean()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'CustomerWithdrawalRequested' AND aggregateid = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    // 두 번째 접수는 409
    assertThat(call("POST", token, "/me/withdrawals", REAUTH_BODY).status()).isEqualTo(409);
    // 목록·조회
    assertThat(
            call("GET", token, "/me/withdrawals", null)
                .body()
                .at("/items/0/withdrawalId")
                .asString())
        .isEqualTo(wid);
    assertThat(call("GET", TestJwt.customer("CU-OTHER"), "/me/withdrawals/" + wid, null).status())
        .isEqualTo(404);

    // 철회 → ACTIVE
    Res revoked =
        call(
            "POST",
            token,
            "/me/withdrawals/" + wid + "/revocations",
            Map.of("provider", "KAKAO", "providerToken", "id-token"));
    assertThat(revoked.status()).as(revoked.body().toString()).isEqualTo(200);
    assertThat(revoked.body().path("state").asString()).isEqualTo("REVOKED");
    assertThat(status(id)).isEqualTo("ACTIVE");

    // 조회 실패 → UNKNOWN(완료하지 않음)
    order.resetAll();
    order.stubFor(get(urlPathMatching(ELIGIBILITY)).willReturn(aResponse().withStatus(503)));
    Res unknown = call("POST", token, "/me/withdrawals", REAUTH_BODY);
    assertThat(unknown.body().path("state").asString()).isEqualTo("UNKNOWN");
    String wid2 = unknown.body().path("withdrawalId").asString();
    assertThat(status(id)).isEqualTo("WITHDRAW_PENDING");

    // 의무 끝 → 다시 확인하면 완료
    eligibility(order, true, null, null);
    withdrawals.check(wid2); // 주기 작업(WithdrawalJobs.recheck)이 부르는 것과 같다
    JsonNode done = call("GET", token, "/me/withdrawals/" + wid2, null).body();
    assertThat(done.path("state").asString()).isEqualTo("COMPLETED");
    assertThat(status(id)).isEqualTo("WITHDRAW_COMPLETED");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT name, phone, email, nickname, customer_number FROM customer WHERE account_id = ?",
            id);
    assertThat(row.get("name")).isNull();
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("email")).isNull();
    assertThat(row.get("nickname")).isNull();
    assertThat(row.get("customer_number")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_address WHERE owner_type = 'ACCOUNT' AND owner_id = ?",
                Integer.class,
                id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT released_at IS NOT NULL FROM customer_identity WHERE account_id = ?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'CustomerWithdrawalCompleted' AND aggregateid = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    // 완료된 탈퇴는 철회 불가
    assertThat(
            call(
                    "POST",
                    token,
                    "/me/withdrawals/" + wid2 + "/revocations",
                    Map.of("provider", "KAKAO", "providerToken", "id-token"))
                .status())
        .isEqualTo(409);
  }

  @Autowired com.pmart.customer.application.WithdrawalService withdrawals;
}
