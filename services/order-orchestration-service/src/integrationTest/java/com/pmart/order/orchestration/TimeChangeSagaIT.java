package com.pmart.order.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.TimeChangeSagaService;
import com.pmart.order.orchestration.infrastructure.TimeChangeStore.Row;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * S4 예약 시간 변경 사가(2026-11-16): 새 슬롯·여력 임시(기존 그대로) → 날짜가 바뀌면 LOT 약속일 → 작업 지시 시간 변경(확정 지점) → 새 점유 확정 →
 * order CONFIRMED. 준비가 먼저 시작되면 새 것만 해제·약속일 되돌림 → REJECTED. 시간이 바뀐 주문의 취소는 새 점유를 해제한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-capacity-base-url=${parts.url}",
      "pmart.orchestration.delivery-capacity-base-url=${parts.url}",
      "pmart.orchestration.payment-base-url=${parts.url}",
      "pmart.orchestration.order-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H",
      "pmart.test.context=time-change"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class TimeChangeSagaIT extends PostgresKafkaContainerSupport {

  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TimeChangeSagaService sagas;

  @InjectWireMock("parts")
  WireMockServer parts;

  static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

  @BeforeEach
  void setUp() {
    parts.resetAll();
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  Map<String, Object> problem(String code) {
    return Map.of(
        "type",
        "about:blank",
        "title",
        code,
        "status",
        409,
        "code",
        code,
        "traceId",
        "t",
        "detail",
        code);
  }

  void completedCheckout(String orderId) {
    String sagaId = "SAGA-" + UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO checkout_saga (saga_id, draft_id, order_id, purchase_intent_id,"
            + " origin_request_id, customer_ref, mfc_id, delivery_mode, amount, accepted_quote_version,"
            + " lines, demand, state, current_step, quote_expires_at, started_at, updated_at,"
            + " finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,'[]'::jsonb,'{\"lineCount\":1}'::jsonb,"
            + " 'COMPLETED', 'CONFIRM_ORDER',?,?,?,?)",
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
            new String[] {"SLOT_HOLD", "SH-" + orderId, "delivery-capacity-service"})) {
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

  /** 참여자: 새 슬롯·여력 임시(새 ID), 확정·해제·약속일·결과 알림 성공. 작업 지시 시간 변경은 work 로. */
  void participants(String orderId, ResponseDefinitionBuilder work) {
    Map<String, Object> ok = Map.of("status", "APPLIED", "requestId", "r", "resultId", "x");
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/work-orders"))
            .willReturn(json(200, Map.of("fulfillmentId", "FUL-" + orderId, "orderId", orderId))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/slot-holds"))
            .willReturn(json(201, Map.of("holdId", "SH-NEW-" + orderId, "state", "HELD"))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/capacity-holds"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "status", "APPLIED", "requestId", "r", "resultId", "CH-NEW-" + orderId))));
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching(
                    "/api/v1/internal/(slot|capacity)-holds/.*/(confirmations|releases)"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching("/api/v1/internal/reservations/.*/promised-date-changes"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/work-orders/FUL-" + orderId + "/reschedules"))
            .willReturn(work));
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching(
                    "/api/v1/internal/orders/.*/reservation-time-changes/.*/results"))
            .willReturn(json(200, ok)));
  }

  Row await(String requestId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var r = sagas.byRequest(requestId);
      if (r.isPresent() && !"RUNNING".equals(r.get().state())) {
        return r.get();
      }
      r.ifPresent(x -> sagas.advance(x.sagaId()));
      Thread.sleep(200);
    }
    throw new AssertionError("S4 사가가 끝나지 않음: " + sagas.byRequest(requestId));
  }

  TimeChangeSagaService.Start start(String orderId, String requestId, LocalDate newDate) {
    return new TimeChangeSagaService.Start(
        orderId, requestId, "MFC-1", TODAY, "18:00", "19:00", newDate, "20:00", "21:00");
  }

  @Test
  void confirmedChangeReplacesHoldsMovesLotDateAndReportsOrder() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    completedCheckout(o);
    participants(
        o, json(200, Map.of("status", "APPLIED", "requestId", "r", "resultId", "FUL-" + o)));
    String req = "RTC-" + UUID.randomUUID();
    sagas.start(start(o, req, TODAY.plusDays(1)));
    Row r = await(req);
    assertThat(r.outcome()).isEqualTo("CONFIRMED");
    assertThat(r.newSlotHoldId()).isEqualTo("SH-NEW-" + o);
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/slot-holds"))
            .withRequestBody(matchingJsonPath("$.newSlotFor", WireMock.equalTo("SH-" + o)))
            .withRequestBody(matchingJsonPath("$.window.start", WireMock.equalTo("20:00"))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/capacity-holds"))
            .withRequestBody(matchingJsonPath("$.replacesHoldId", WireMock.equalTo("CH-" + o))));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/promised-date-changes"))
            .withRequestBody(
                matchingJsonPath(
                    "$.promisedDate", WireMock.equalTo(TODAY.plusDays(1).toString()))));
    parts.verify(
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/slot-holds/SH-NEW-" + o + "/confirmations")));
    parts.verify(
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/capacity-holds/CH-NEW-" + o + "/confirmations")));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/orders/"
                        + o
                        + "/reservation-time-changes/"
                        + req
                        + "/results"))
            .withRequestBody(matchingJsonPath("$.result", WireMock.equalTo("CONFIRMED"))));
    parts.verify(
        0,
        postRequestedFor(
            WireMock.urlPathMatching("/api/v1/internal/(slot|capacity)-holds/.*/releases")));
    // 같은 요청 다시 → 같은 사가
    assertThat(sagas.start(start(o, req, TODAY.plusDays(1)))).isEqualTo(r.sagaId());
  }

  @Test
  void preparationStartedFirstReleasesNewHoldsAndRevertsLotDate() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    completedCheckout(o);
    participants(o, json(409, problem("PREPARATION_STARTED")));
    String req = "RTC-" + UUID.randomUUID();
    sagas.start(start(o, req, TODAY.plusDays(1)));
    Row r = await(req);
    assertThat(r.outcome()).isEqualTo("REJECTED");
    assertThat(r.reason()).isEqualTo("PREPARATION_STARTED");
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/slot-holds/SH-NEW-" + o + "/releases")));
    parts.verify(
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/capacity-holds/CH-NEW-" + o + "/releases")));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/promised-date-changes"))
            .withRequestBody(
                matchingJsonPath("$.promisedDate", WireMock.equalTo(TODAY.toString()))));
    parts.verify(
        0,
        postRequestedFor(
            WireMock.urlPathMatching("/api/v1/internal/(slot|capacity)-holds/.*/confirmations")));
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/orders/"
                        + o
                        + "/reservation-time-changes/"
                        + req
                        + "/results"))
            .withRequestBody(matchingJsonPath("$.result", WireMock.equalTo("REJECTED")))
            .withRequestBody(
                matchingJsonPath("$.reason", WireMock.equalTo("PREPARATION_STARTED"))));
  }

  @Test
  void fullSlotRejectsWithoutTouchingAnythingElse() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    completedCheckout(o);
    participants(
        o, json(200, Map.of("status", "APPLIED", "requestId", "r", "resultId", "FUL-" + o)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/slot-holds"))
            .atPriority(1)
            .willReturn(json(422, problem("CAPACITY_EXCEEDED"))));
    String req = "RTC-" + UUID.randomUUID();
    sagas.start(start(o, req, TODAY));
    Row r = await(req);
    assertThat(r.outcome()).isEqualTo("REJECTED");
    assertThat(r.reason()).isEqualTo("CAPACITY_EXCEEDED");
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/capacity-holds")));
    parts.verify(
        0,
        postRequestedFor(WireMock.urlPathMatching("/api/v1/internal/work-orders/.*/reschedules")));
    parts.verify(0, postRequestedFor(WireMock.urlPathMatching("/api/v1/internal/reservations/.*")));
  }
}
