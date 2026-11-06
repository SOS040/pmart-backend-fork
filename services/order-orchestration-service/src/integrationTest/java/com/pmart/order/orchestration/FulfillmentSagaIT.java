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
import com.pmart.order.orchestration.application.FulfillmentSagaService;
import com.pmart.order.orchestration.infrastructure.FulfillmentStore.SagaRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S2 이행 요청(슬라이스 6a): OrderConfirmed → ConfirmOrder 접수 → 완료. 재고 불일치 거절 → 1분마다 원래 예약 재확인 → 새 작업 버전,
 * 횟수·창·슬롯 시작 초과 → 시스템 자동 전체 취소(claim), 재시도 대상 아님 → 바로 자동 취소, 센터 설정 없음 → CONFIG_REQUIRED, 고객 취소가 먼저면
 * 닫음, 전환 시각 전 주문은 무시. fulfillment·inventory·claim 은 한 WireMock(주문 ID 로 응답을 나눈다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.claim-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H", // 시험이 진행을 직접 부른다
      "pmart.orchestration.fulfillment.start-orders-confirmed-after=2026-11-01T00:00:00Z",
      "pmart.orchestration.fulfillment.retry-window.[MFC-T1]=PT10M",
      "pmart.orchestration.fulfillment.max-new-versions.[MFC-T1]=2"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class FulfillmentSagaIT extends PostgresKafkaContainerSupport {

  static final String SVC = TestJwt.service("bo");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired FulfillmentSagaService sagas;
  @Autowired TransactionTemplate tx;
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
  }

  // ---------- 도우미 ----------

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  Map<String, Object> confirmed(String orderId, String mfcId, Instant confirmedAt, Instant slot) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("orderVersion", 1);
    p.put("accountId", "CU-1");
    p.put("mfcId", mfcId);
    p.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId", orderId + "-L1",
                "sku", "E-00000001",
                "qty", 2,
                "unitsPerSku", 1,
                "weightG", 600,
                "unitPrice", 1500,
                "discountShare", 0,
                "productName", "국산콩 두부 300g")));
    p.put("deliveryMode", slot == null ? "SINGLE" : "SCHEDULED");
    if (slot != null) {
      p.put(
          "arrivalWindow",
          Map.of(
              "start",
              slot.toString(),
              "end",
              slot.plus(Duration.ofHours(1)).toString(),
              "meaning",
              "ARRIVAL"));
    }
    p.put("outOfStockPreference", "CANCEL_ITEM");
    p.put("reservationId", "RSV-" + orderId);
    p.put("paymentAttemptId", "PAY-" + orderId);
    p.put("totalAmount", 3100);
    p.put(
        "shipments",
        List.of(
            Map.of(
                "shipmentId",
                orderId + "-S1",
                "weightG",
                600,
                "lines",
                List.of(Map.of("orderLineId", orderId + "-L1", "qty", 2)))));
    p.put("confirmedAt", confirmedAt.toString());
    return p;
  }

  void deliver(Map<String, Object> payload) {
    var e =
        EventEnvelopeFixtures.envelope("OrderConfirmed")
            .from("order-service")
            .aggregate("Order", (String) payload.get("orderId"), 1)
            .payload(payload)
            .build();
    tx.executeWithoutResult(s -> sagas.onOrderEvent(e));
  }

  /** ConfirmOrder 응답(작업 버전별). */
  void work(String orderId, int version, String decision, String reason) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("fulfillmentId", "FUL-" + orderId);
    d.put("orderId", orderId);
    d.put("workVersion", version);
    d.put("decision", decision);
    if (reason != null) {
      d.put("rejectReason", reason);
      d.put("retryable", "INVENTORY_MISMATCH".equals(reason));
    }
    d.put("decidedAt", Instant.now().toString());
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/work-orders"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .withRequestBody(
                matchingJsonPath("$.workVersion", WireMock.equalTo(String.valueOf(version))))
            .willReturn(json(201, d)));
  }

  void reservation(String orderId, String partState) {
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + orderId,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(Map.of("sku", "E-00000001", "qty", 2, "state", partState))))));
  }

  void claimAccepts(String orderId) {
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/claims/system-cancellations"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "claimId",
                        "CLM-" + orderId,
                        "orderId",
                        orderId,
                        "state",
                        "STOP_PENDING"))));
  }

  SagaRow saga(String orderId) {
    Optional<SagaRow> s =
        jdbc
            .queryForList(
                "SELECT saga_id FROM fulfillment_saga WHERE order_id = ?", String.class, orderId)
            .stream()
            .findFirst()
            .flatMap(sagas::find);
    return s.orElseThrow(() -> new AssertionError("사가 없음 " + orderId));
  }

  SagaRow awaitState(String orderId, String state) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var ids =
          jdbc.queryForList(
              "SELECT saga_id FROM fulfillment_saga WHERE order_id = ? AND state = ?",
              String.class,
              orderId,
              state);
      if (!ids.isEmpty()) {
        return sagas.find(ids.get(0)).orElseThrow();
      }
      Thread.sleep(100);
    }
    return saga(orderId);
  }

  /** 다음 재확인 시각을 지금으로 당긴 뒤 진행. */
  void recheckNow(String orderId) {
    SagaRow s = saga(orderId);
    jdbc.update(
        "UPDATE fulfillment_saga SET next_check_at = now() - interval '1 second' WHERE saga_id = ?",
        s.sagaId());
    sagas.advance(s.sagaId());
  }

  int confirmCalls(String orderId) {
    return parts
        .findAll(
            postRequestedFor(urlPathEqualTo("/api/v1/internal/work-orders"))
                .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId))))
        .size();
  }

  int cancelCalls(String orderId) {
    return parts
        .findAll(
            postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/system-cancellations"))
                .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId))))
        .size();
  }

  JsonNode get(String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(SVC)).retrieve().toEntity(String.class);
    return mapper.readTree(e.getBody() == null ? "{}" : e.getBody());
  }

  static String order() {
    return "ORD-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // ---------- 시험 ----------

  @Test
  void orderConfirmedEventStartsAndWorkAccepted() throws Exception {
    String o = order();
    work(o, 1, "ACCEPTED", null);
    // 사건(Kafka)으로 시작
    String json =
        EventEnvelopeFixtures.envelope("OrderConfirmed")
            .from("order-service")
            .aggregate("Order", o, 1)
            .payload(confirmed(o, "MFC-T1", Instant.now(), null))
            .buildJson();
    kafka.send("order.order.v1", o, json).join();
    SagaRow s = awaitState(o, "COMPLETED");
    assertThat(s.state()).isEqualTo("COMPLETED");
    assertThat(s.outcome()).isEqualTo("WORK_ACCEPTED");
    assertThat(s.fulfillmentId()).isEqualTo("FUL-" + o);

    // ConfirmOrder 본문: 작업 버전 1·사가 ID·출고 행에 SKU·상품명
    var req =
        parts
            .findAll(
                postRequestedFor(urlPathEqualTo("/api/v1/internal/work-orders"))
                    .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(o))))
            .get(0);
    JsonNode b = mapper.readTree(req.getBodyAsString());
    assertThat(req.getHeader("Idempotency-Key")).isEqualTo(s.sagaId() + ":REQUEST_FULFILLMENT:v1");
    assertThat(b.path("workVersion").asInt()).isEqualTo(1);
    assertThat(b.path("sagaId").asString()).isEqualTo(s.sagaId());
    JsonNode line = b.path("shipments").path(0).path("lines").path(0);
    assertThat(b.path("shipments").path(0).path("shipmentId").asString()).isEqualTo(o + "-S1");
    assertThat(line.path("sku").asString()).isEqualTo("E-00000001");
    assertThat(line.path("productName").asString()).isEqualTo("국산콩 두부 300g");
    assertThat(line.path("qty").asInt()).isEqualTo(2);

    // 재전달은 사가를 새로 만들지 않는다
    deliver(confirmed(o, "MFC-T1", Instant.now(), null));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_saga WHERE order_id = ?", Integer.class, o))
        .isEqualTo(1);
    assertThat(confirmCalls(o)).isEqualTo(1);

    // 조회: 상세·주문 ID 목록
    JsonNode d = get("/internal/sagas/" + s.sagaId());
    assertThat(d.path("sagaType").asString()).isEqualTo("S2_FULFILLMENT");
    assertThat(d.path("steps").path(0).path("state").asString()).isEqualTo("SUCCEEDED");
    JsonNode list = get("/internal/sagas?orderId=" + o + "&sagaType=S2_FULFILLMENT");
    assertThat(list.path("items")).hasSize(1);
  }

  @Test
  void ordersConfirmedBeforeCutoverAreIgnored() {
    String o = order();
    deliver(confirmed(o, "MFC-T1", Instant.parse("2026-10-30T00:00:00Z"), null));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_saga WHERE order_id = ?", Integer.class, o))
        .isZero();
  }

  @Test
  void inventoryMismatchWaitsThenRecoveredReservationGetsNewWorkVersion() throws Exception {
    String o = order();
    work(o, 1, "REJECTED", "INVENTORY_MISMATCH");
    work(o, 2, "ACCEPTED", null);
    reservation(o, "BLOCKED");
    deliver(confirmed(o, "MFC-T1", Instant.now(), null));
    SagaRow w = awaitState(o, "RETRY_WAITING");
    assertThat(w.state()).isEqualTo("RETRY_WAITING");
    assertThat(w.currentStep()).isEqualTo("AWAIT_RECOVERY");
    assertThat(w.firstRejectedAt()).isNotNull();
    assertThat(w.nextCheckAt()).isAfter(Instant.now().plusSeconds(30));

    // 재확인: 아직 못 되찾음 → 그대로 대기(새 버전 안 보냄)
    recheckNow(o);
    assertThat(saga(o).state()).isEqualTo("RETRY_WAITING");
    assertThat(confirmCalls(o)).isEqualTo(1);
    // 원래 예약이 확정으로 돌아옴 → 작업 버전 2 → 접수
    reservation(o, "CONFIRMED");
    recheckNow(o);
    SagaRow done = saga(o);
    assertThat(done.state()).isEqualTo("COMPLETED");
    assertThat(done.outcome()).isEqualTo("WORK_ACCEPTED");
    assertThat(done.workVersion()).isEqualTo(2);
    assertThat(sagas.attempts(done.sagaId())).hasSize(2);
    assertThat(cancelCalls(o)).isZero();
  }

  @Test
  void retriesExhaustedThenSystemCancel() throws Exception {
    String o = order();
    work(o, 1, "REJECTED", "INVENTORY_MISMATCH");
    work(o, 2, "REJECTED", "INVENTORY_MISMATCH");
    work(o, 3, "REJECTED", "INVENTORY_MISMATCH");
    reservation(o, "CONFIRMED");
    claimAccepts(o);
    deliver(confirmed(o, "MFC-T1", Instant.now(), null));
    awaitState(o, "RETRY_WAITING");
    recheckNow(o); // v2 거절
    assertThat(saga(o).workVersion()).isEqualTo(2);
    recheckNow(o); // v3 거절 → 새 버전 2번 다 씀 → 자동 취소
    SagaRow s = saga(o);
    assertThat(s.state()).isEqualTo("COMPLETED");
    assertThat(s.outcome()).isEqualTo("CANCEL_REQUESTED");
    assertThat(s.claimId()).isEqualTo("CLM-" + o);
    assertThat(confirmCalls(o)).isEqualTo(3);
    var cancel =
        parts
            .findAll(
                postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/system-cancellations"))
                    .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(o))))
            .get(0);
    JsonNode b = mapper.readTree(cancel.getBodyAsString());
    assertThat(b.path("reason").asString()).isEqualTo("FULFILLMENT_REJECTED");
    assertThat(b.path("sourceRef").asString()).isEqualTo(s.sagaId());
    assertThat(cancel.getHeader("Idempotency-Key"))
        .isEqualTo(s.sagaId() + ":REQUEST_SYSTEM_CANCEL");
  }

  @Test
  void windowCountedFromFirstRejectionExpires() throws Exception {
    String o = order();
    work(o, 1, "REJECTED", "INVENTORY_MISMATCH");
    reservation(o, "BLOCKED");
    claimAccepts(o);
    deliver(confirmed(o, "MFC-T1", Instant.now(), null));
    SagaRow w = awaitState(o, "RETRY_WAITING");
    jdbc.update(
        "UPDATE fulfillment_saga SET first_rejected_at = ? WHERE saga_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))),
        w.sagaId());
    recheckNow(o);
    assertThat(saga(o).outcome()).isEqualTo("CANCEL_REQUESTED");
    assertThat(confirmCalls(o)).isEqualTo(1);
  }

  @Test
  void nonRetryableOrSlotStartedCancelsImmediately() throws Exception {
    // 여력 부족 → 재시도 없이 자동 취소
    String a = order();
    work(a, 1, "REJECTED", "CAPACITY_EXCEEDED");
    claimAccepts(a);
    deliver(confirmed(a, "MFC-T1", Instant.now(), null));
    assertThat(awaitState(a, "COMPLETED").outcome()).isEqualTo("CANCEL_REQUESTED");
    assertThat(cancelCalls(a)).isEqualTo(1);

    // 재고 불일치라도 배송 슬롯 시작이 지났으면 기다리지 않는다
    String b = order();
    work(b, 1, "REJECTED", "INVENTORY_MISMATCH");
    claimAccepts(b);
    deliver(confirmed(b, "MFC-T1", Instant.now(), Instant.now().minus(Duration.ofMinutes(5))));
    assertThat(awaitState(b, "COMPLETED").outcome()).isEqualTo("CANCEL_REQUESTED");
  }

  @Test
  void centerWithoutRetrySettingsIsConfigRequired() throws Exception {
    String o = order();
    work(o, 1, "REJECTED", "INVENTORY_MISMATCH");
    deliver(confirmed(o, "MFC-NOCONF", Instant.now(), null));
    SagaRow s = awaitState(o, "CONFIG_REQUIRED");
    assertThat(s.state()).isEqualTo("CONFIG_REQUIRED");
    // 재평가해도 설정이 없으면 그대로(자동 취소하지 않음)
    sagas.advance(s.sagaId());
    assertThat(saga(o).state()).isEqualTo("CONFIG_REQUIRED");
    assertThat(cancelCalls(o)).isZero();
  }

  @Test
  void customerCancellationClosesWaitingSaga() throws Exception {
    String o = order();
    work(o, 1, "REJECTED", "INVENTORY_MISMATCH");
    reservation(o, "BLOCKED");
    deliver(confirmed(o, "MFC-T1", Instant.now(), null));
    awaitState(o, "RETRY_WAITING");
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO cancellation_saga (saga_id, claim_id, stop_request_id, order_id, scope, lines,"
            + " state, current_step, started_at, updated_at) VALUES (?,?,?,?,'FULL','[]'::jsonb,"
            + " 'RUNNING','STOP_WORK',?,?)",
        "SAGA-C-" + o,
        "CLM-C-" + o,
        "STOP-" + o,
        o,
        now,
        now);
    recheckNow(o);
    SagaRow s = saga(o);
    assertThat(s.state()).isEqualTo("COMPLETED");
    assertThat(s.outcome()).isEqualTo("CLOSED_BY_CANCEL");
    assertThat(cancelCalls(o)).isZero();
  }
}
