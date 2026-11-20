package com.pmart.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 탈퇴 의무 확인(2026-11-20, ADR-0077, 계약 0.16.0): 끝나지 않은 확정 주문 = ORDER_IN_PROGRESS, 확정 주문이 없는 결제 진입은
 * orchestration 사가 상태로(보상 종료 = 의무 없음, 진행·대사·조회 실패 = PAYMENT_UNKNOWN), 결제 진입 전 초안은 의무 아님. 서비스 토큰만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=withdrawal-test"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "orch",
        baseUrlProperties = {"pmart.order.orchestration-base-url"}))
class WithdrawalEligibilityIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderStore store;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("orch")
  WireMockServer orch;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    orch.resetAll();
  }

  ResponseEntity<String> check(String token, String account) {
    return http.get()
        .uri("/internal/accounts/{id}/withdrawal-eligibility", account)
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(String.class);
  }

  JsonNode ok(String account) {
    var e = check(TestJwt.service("customer-service"), account);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    return mapper.readTree(e.getBody());
  }

  /** 확정 주문 하나(행 없이 — 상태만 본다). */
  String order(String account, String status) {
    String orderId = "ORD-WD-" + UUID.randomUUID();
    store.insert(
        new OrderRow(
            orderId,
            account,
            "DRF-" + orderId,
            "MFC-WD-01",
            status,
            1,
            "SINGLE",
            null,
            null,
            null,
            "CANCEL_OOS_ONLY",
            null,
            "ADDR-1",
            1,
            1,
            20_000,
            4_000,
            4_000,
            "STANDARD",
            100,
            24_100,
            false,
            1_500,
            1,
            "PA-" + orderId,
            "PG-" + orderId,
            24_100,
            "RSV-" + orderId,
            null,
            null,
            Instant.now(),
            null),
        List.of());
    return orderId;
  }

  /** 초안. sagaState 가 있으면 결제 진입(주문 ID·사가)을 남기고 사가 조회를 그 상태로 고정한다. 반환 = 주문 ID(없으면 초안 ID). */
  String draft(String account, @Nullable String sagaId, @Nullable String sagaState) {
    String draftId = "DRF-WD-" + UUID.randomUUID();
    String orderId = sagaId == null ? null : "ORD-WD-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO order_draft (draft_id, account_id, purchase_intent_id, status, mfc_id, snapshot,"
            + " version, created_at, quote_expires_at, order_id, saga_id) VALUES"
            + " (?,?,?,?,?,'{}'::jsonb,1,?,?,?,?)",
        draftId,
        account,
        "PI-" + draftId,
        sagaId == null ? "DRAFT" : "RESOURCE_PENDING",
        "MFC-WD-01",
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now().plusSeconds(600)),
        orderId,
        sagaId);
    if (sagaId != null && sagaState != null) {
      orch.stubFor(
          WireMock.get(urlPathEqualTo("/api/v1/internal/sagas/" + sagaId))
              .willReturn(
                  aResponse()
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"sagaId\":\"" + sagaId + "\",\"state\":\"" + sagaState + "\"}")));
    }
    return orderId == null ? draftId : orderId;
  }

  static String account() {
    return "ACC-WD-" + UUID.randomUUID();
  }

  @Test
  void customerWithoutOrdersIsEligible() {
    JsonNode body = ok(account());
    assertThat(body.path("eligible").asBoolean()).isTrue();
    assertThat(body.path("openObligations").isEmpty()).isTrue();
    assertThat(body.path("asOf").path("asOf").isString()).isTrue();
    assertThat(body.path("asOf").path("freshness").asString()).isEqualTo("CURRENT");
  }

  @Test
  void finishedOrdersAndPlainDraftsAreNotObligations() {
    String acc = account();
    order(acc, "COMPLETED");
    order(acc, "CANCELLED");
    order(acc, "EXPIRED");
    draft(acc, null, null); // 결제 진입 전 초안
    draft(acc, "SAGA-" + UUID.randomUUID(), "COMPENSATED"); // 보상 종료된 결제 진입
    // 다른 고객의 진행 중 주문은 상관없다
    order(account(), "CONFIRMED");

    JsonNode body = ok(acc);
    assertThat(body.path("eligible").asBoolean()).as(body.toString()).isTrue();
    assertThat(body.path("openObligations").isEmpty()).isTrue();
  }

  @Test
  void unfinishedOrdersAndUnknownPaymentsBlock() {
    String acc = account();
    String confirmed = order(acc, "CONFIRMED");
    String delivering = order(acc, "IN_DELIVERY");
    order(acc, "COMPLETED");
    String hidden = order(acc, "PARTIALLY_DELIVERED");
    store.hide(hidden, Instant.now()); // 목록에서 숨긴 주문도 미결로 센다
    String reconciling = draft(acc, "SAGA-" + UUID.randomUUID(), "RECONCILIATION_REQUIRED");
    String waiting = draft(acc, "SAGA-" + UUID.randomUUID(), "WAITING_RESULT");
    String completedSaga = draft(acc, "SAGA-" + UUID.randomUUID(), "COMPLETED");
    // 사가 조회 실패(stub 없음 → 404) — 결과를 모르니 미결
    String lookupFailed = draft(acc, "SAGA-" + UUID.randomUUID(), null);

    JsonNode body = ok(acc);
    assertThat(body.path("eligible").asBoolean()).isFalse();
    JsonNode obligations = body.path("openObligations");
    assertThat(obligations.size()).as(body.toString()).isEqualTo(7);
    List<String> inProgress = refs(obligations, "ORDER_IN_PROGRESS");
    List<String> unknown = refs(obligations, "PAYMENT_UNKNOWN");
    assertThat(inProgress).containsExactlyInAnyOrder(confirmed, delivering, hidden, completedSaga);
    assertThat(unknown).containsExactlyInAnyOrder(reconciling, waiting, lookupFailed);
  }

  @Test
  void onlyServiceTokens() {
    String acc = account();
    assertThat(check(TestJwt.customer(acc), acc).getStatusCode().value()).isEqualTo(403);
  }

  static List<String> refs(JsonNode obligations, String kind) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode o : obligations) {
      if (kind.equals(o.path("kind").asString())) {
        out.add(o.path("ref").asString());
      }
    }
    return out;
  }
}
