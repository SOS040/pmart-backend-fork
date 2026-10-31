package com.pmart.order.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.CancellationSagaService;
import com.pmart.order.orchestration.infrastructure.CancellationStore.CancellationRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S3 취소 사가(피킹 전 전체 취소): 현장 중지 → S1 확정 점유 해제(원예약 조회로 확인) → 주문 수량 반영. 판정 대기는 재조회로 잇고, 인계됨이면 해제·주문 반영
 * 없이 상신. CancellationRequested 사건으로도 시작된다. 참여 정본·auth 는 한 WireMock(주문 ID 로 응답을 나눈다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${parts.url}/api/v1/internal/service-tokens",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-capacity-base-url=${parts.url}",
      "pmart.orchestration.delivery-capacity-base-url=${parts.url}",
      "pmart.orchestration.payment-base-url=${parts.url}",
      "pmart.orchestration.order-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H" // 시험이 진행을 직접 부른다
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class CancellationSagaIT extends PostgresKafkaContainerSupport {

  static final String CLAIM = TestJwt.service("claim-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired CancellationSagaService sagas;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("parts")
  WireMockServer parts;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    parts.resetAll();
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "access_token",
                        TestJwt.service("order-orchestration-service"),
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  /** S1 이 주문을 확정했다(COMPLETED) — 재고·여력·슬롯 점유가 보상 로그에 있다. */
  void completedCheckout(String orderId) {
    String sagaId = "SAGA-" + UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO checkout_saga (saga_id, draft_id, order_id, purchase_intent_id,"
            + " origin_request_id, customer_ref, mfc_id, delivery_mode, amount, accepted_quote_version,"
            + " lines, demand, state, current_step, quote_expires_at, started_at, updated_at,"
            + " finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,'[]'::jsonb,'{}'::jsonb,'COMPLETED',"
            + " 'CONFIRM_ORDER',?,?,?,?)",
        sagaId,
        "DRF-" + orderId,
        orderId,
        "PI-" + orderId,
        "k",
        "CU-1",
        "MFC-1",
        "SCHEDULED",
        10_100,
        1,
        now,
        now,
        now,
        now);
    for (String[] r :
        List.of(
            new String[] {"INVENTORY_RESERVATION", "RSV-" + orderId, "mfc-inventory-service"},
            new String[] {"CAPACITY_HOLD", "CH-" + orderId, "mfc-capacity-service"},
            new String[] {"SLOT_HOLD", "SH-" + orderId, "delivery-capacity-service"},
            new String[] {"PAYMENT_APPROVAL", "PAY-" + orderId, "payment-service"})) {
      jdbc.update(
          "INSERT INTO saga_compensation (saga_id, resource_type, resource_id, owner_system,"
              + " acquired_at) VALUES (?,?,?,?,?)",
          sagaId,
          r[0],
          r[1],
          r[2],
          now);
    }
  }

  /** 해제 명령·원예약 조회(state 를 돌려준다). */
  void releasable(String orderId, String state) {
    for (String[] r :
        List.of(
            new String[] {"/api/v1/internal/reservations/RSV-", "reservationId"},
            new String[] {"/api/v1/internal/capacity-holds/CH-", "holdId"},
            new String[] {"/api/v1/internal/slot-holds/SH-", "holdId"})) {
      String base = r[0] + orderId;
      parts.stubFor(
          WireMock.post(urlPathEqualTo(base + "/releases"))
              .willReturn(json(200, Map.of("status", "APPLIED", "requestId", "x"))));
      parts.stubFor(
          WireMock.get(urlPathEqualTo(base))
              .willReturn(json(200, Map.of(r[1], "x", "state", state))));
    }
  }

  void orderChange(String orderId) {
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/line-quantity-changes"))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "requestId", "x", "resultId", orderId))));
  }

  Map<String, Object> stopResult(String stopId, String orderId, String state, List<?> packages) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("stopRequestId", stopId);
    b.put("orderId", orderId);
    b.put("state", state);
    b.put("packages", packages);
    b.put("notStartedLines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 2)));
    return b;
  }

  void stop(String stopId, String orderId, Map<String, Object> result) {
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/stop-requests"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(json(200, result)));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stop-requests/" + stopId))
            .willReturn(json(200, result)));
  }

  int start(String key, String claimId, String stopId, String orderId) {
    var e =
        http.post()
            .uri("/internal/cancellation-sagas")
            .headers(
                h -> {
                  h.setBearerAuth(CLAIM);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "claim-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "claimId",
                    claimId,
                    "stopRequestId",
                    stopId,
                    "orderId",
                    orderId,
                    "scope",
                    "FULL",
                    "reason",
                    "CUSTOMER_REQUEST",
                    "lines",
                    List.of(Map.of("orderLineId", orderId + "-L1", "qty", 2))))
            .retrieve()
            .toEntity(String.class);
    return e.getStatusCode().value();
  }

  CancellationRow saga(String stopId) {
    return sagas.byStopRequest(stopId).orElseThrow();
  }

  CancellationRow awaitState(String stopId, String state) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var s = sagas.byStopRequest(stopId);
      if (s.isPresent() && state.equals(s.get().state())) {
        return s.get();
      }
      Thread.sleep(100);
    }
    return sagas.byStopRequest(stopId).orElseThrow();
  }

  @Test
  void cancelBeforePickReleasesAndCancelsOrder() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    completedCheckout(orderId);
    stop(stopId, orderId, stopResult(stopId, orderId, "RESOLVED", List.of()));
    releasable(orderId, "RELEASED");
    orderChange(orderId);

    assertThat(start("CLM-1:start", "CLM-1", stopId, orderId)).isEqualTo(202);
    CancellationRow s = awaitState(stopId, "COMPLETED");
    assertThat(s.state()).isEqualTo("COMPLETED");
    assertThat(s.resources()).hasSize(3);
    assertThat(s.resources().findValuesAsString("state")).containsOnly("RELEASED");

    // 같은 중지 요청 ID 재시작 → 같은 사가
    assertThat(start(UUID.randomUUID().toString(), "CLM-1", stopId, orderId)).isEqualTo(202);
    assertThat(saga(stopId).sagaId()).isEqualTo(s.sagaId());

    // 명령: 단계 키·취소 사유·주문 수량 반영 본문
    parts.verify(
        1,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/stop-requests"))
            .withHeader("Idempotency-Key", WireMock.equalTo(s.sagaId() + ":STOP_WORK"))
            .withRequestBody(matchingJsonPath("$.claimId", WireMock.equalTo("CLM-1"))));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId + "/releases"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("CANCEL_BEFORE_PICK")))
            .withRequestBody(matchingJsonPath("$.sourceRef", WireMock.equalTo(stopId))));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/capacity-holds/CH-" + orderId + "/releases"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("ORDER_CANCELLED"))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/slot-holds/SH-" + orderId + "/releases"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("ORDER_CANCELLED"))));
    parts.verify(
        1,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/line-quantity-changes"))
            .withHeader("Idempotency-Key", WireMock.equalTo(s.sagaId() + ":APPLY_ORDER_CANCEL"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("CANCEL_BEFORE_SHIP")))
            .withRequestBody(matchingJsonPath("$.claimId", WireMock.equalTo("CLM-1")))
            .withRequestBody(matchingJsonPath("$.changes[0].qty", WireMock.equalTo("2"))));
    // 결제 승인은 해제 대상이 아니다(환불은 claim 이 한다)
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo(
                "/api/v1/internal/payments/PAY-" + orderId + "/compensation-cancellations")));
  }

  @Test
  void waitsForHandoverVerdictThenContinues() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    completedCheckout(orderId);
    stop(
        stopId,
        orderId,
        stopResult(
            stopId,
            orderId,
            "PENDING",
            List.of(Map.of("packageId", "PKG-1", "verdict", "HANDOVER_IN_PROGRESS"))));
    releasable(orderId, "RELEASED");
    orderChange(orderId);

    assertThat(start(UUID.randomUUID().toString(), "CLM-2", stopId, orderId)).isEqualTo(202);
    CancellationRow waiting = awaitState(stopId, "WAITING_RESULT");
    assertThat(waiting.state()).isEqualTo("WAITING_RESULT");
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId + "/releases")));

    // fence 결과: 중지 확정 → 재조회로 이어서 완료
    stop(
        stopId,
        orderId,
        stopResult(
            stopId,
            orderId,
            "RESOLVED",
            List.of(Map.of("packageId", "PKG-1", "verdict", "STOPPED"))));
    sagas.advance(waiting.sagaId());
    assertThat(saga(stopId).state()).isEqualTo("COMPLETED");
  }

  @Test
  void handedOverEscalatesWithoutReleasing() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    completedCheckout(orderId);
    stop(
        stopId,
        orderId,
        stopResult(
            stopId,
            orderId,
            "RESOLVED",
            List.of(Map.of("packageId", "PKG-1", "verdict", "HANDED_OVER"))));
    releasable(orderId, "RELEASED");
    orderChange(orderId);

    assertThat(start(UUID.randomUUID().toString(), "CLM-3", stopId, orderId)).isEqualTo(202);
    CancellationRow s = awaitState(stopId, "ESCALATED");
    assertThat(s.state()).isEqualTo("ESCALATED");
    assertThat(s.lastErrorCode()).isEqualTo("HANDED_OVER");
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId + "/releases")));
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/line-quantity-changes")));
  }

  @Test
  void releaseUnconfirmedWaitsThenRetriesWithSameKeys() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    completedCheckout(orderId);
    stop(stopId, orderId, stopResult(stopId, orderId, "RESOLVED", List.of()));
    releasable(orderId, "CONFIRMED"); // 해제 응답은 왔지만 원예약 조회가 아직 확정 — 해제로 기록하지 않는다
    orderChange(orderId);

    assertThat(start(UUID.randomUUID().toString(), "CLM-4", stopId, orderId)).isEqualTo(202);
    for (int i = 0; i < 50 && saga(stopId).resources() == null; i++) {
      Thread.sleep(100);
    }
    CancellationRow s = saga(stopId);
    assertThat(s.state()).isEqualTo("RUNNING");
    assertThat(s.currentStep()).isEqualTo("RELEASE_RESOURCES");
    assertThat(s.resources().findValuesAsString("state")).containsOnly("RELEASE_UNKNOWN");
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/line-quantity-changes")));

    releasable(orderId, "RELEASED");
    sagas.advance(s.sagaId());
    assertThat(saga(stopId).state()).isEqualTo("COMPLETED");
    parts.verify(
        2,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId + "/releases"))
            .withHeader(
                "Idempotency-Key",
                WireMock.equalTo(s.sagaId() + ":RELEASE:INVENTORY_RESERVATION")));
  }

  @Test
  void cancellationRequestedEventStartsSaga() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    completedCheckout(orderId);
    stop(stopId, orderId, stopResult(stopId, orderId, "RESOLVED", List.of()));
    releasable(orderId, "RELEASED");
    orderChange(orderId);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("claimId", "CLM-5");
    payload.put("orderId", orderId);
    payload.put("stopRequestId", stopId);
    payload.put("scope", "FULL");
    payload.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 2)));
    payload.put("reason", "CUSTOMER_REQUEST");
    String json =
        EventEnvelopeFixtures.envelope("CancellationRequested")
            .from("claim-service")
            .aggregate("Claim", "CLM-5", 1)
            .payload(payload)
            .buildJson();
    kafka.send("order.claim.v1", "CLM-5", json).join();

    JsonNode lines = null;
    for (int i = 0; i < 150; i++) {
      var s = sagas.byStopRequest(stopId);
      if (s.isPresent() && "COMPLETED".equals(s.get().state())) {
        lines = s.get().lines();
        break;
      }
      Thread.sleep(100);
    }
    assertThat(lines).as("사건으로 시작한 사가가 완료돼야 한다").isNotNull();
    assertThat(saga(stopId).claimId()).isEqualTo("CLM-5");
  }
}
