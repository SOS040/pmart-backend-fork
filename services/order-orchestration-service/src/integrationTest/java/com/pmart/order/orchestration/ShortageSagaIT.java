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
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.ShortageSagaService;
import com.pmart.order.orchestration.infrastructure.ShortageStore.ShortageRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * 결품 사가(IV-09 2부, ADR-0045): LineShortageReported → 품절 상품만 취소면 작업 수량 취소 → 이행부족 해제 → 주문 수량
 * 변경(SHORTAGE) → 부분 환불 클레임 → 완료. 전체 취소 선택·주문 전체 결품은 상신(수동). 참여 정본은 한 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.claim-base-url=${parts.url}",
      "pmart.orchestration.order-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H",
      "pmart.orchestration.fulfillment.start-orders-confirmed-after=2026-11-01T00:00:00Z"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class ShortageSagaIT extends PostgresKafkaContainerSupport {

  @Autowired ObjectMapper mapper;
  @Autowired ShortageSagaService sagas;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("parts")
  WireMockServer parts;

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

  void shortage(String o, String preference, int qty) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o);
    p.put("fulfillmentId", "FUL-" + o);
    p.put("orderLineId", o + "-L1");
    p.put("sku", "E-00000001");
    p.put("lot", "LOT-1");
    p.put("shortQty", qty);
    p.put("reason", "DAMAGED");
    p.put("alreadyHandedOverQty", 0);
    p.put("mfcId", "MFC-T1");
    p.put("reservationId", "RSV-" + o);
    p.put("outOfStockPreference", preference);
    p.put("reportedAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("LineShortageReported")
            .from("mfc-fulfillment-service")
            .aggregate("Fulfillment", o, 3)
            .payload(p)
            .buildJson();
    kafka.send("mfc.fulfillment.v1", o, json).join();
  }

  /** 주문 원장: L1 3개, L2 2개 모두 미이행. */
  void ledger(String o) {
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + o))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "orderId",
                        o,
                        "lines",
                        List.of(
                            Map.of("orderLineId", o + "-L1", "unfulfilledQty", 3),
                            Map.of("orderLineId", o + "-L2", "unfulfilledQty", 2))))));
  }

  void participantsAccept(String o) {
    Map<String, Object> ok = Map.of("status", "APPLIED", "resultId", "x");
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/work-orders/FUL-" + o + "/shortage-resolutions"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/shortfall-releases"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(o)))
            .willReturn(json(201, Map.of("claimId", "CLM-" + o, "state", "IN_PROGRESS"))));
  }

  ShortageRow await(String o, String state) throws InterruptedException {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      var rows = sagas.byOrder(o);
      if (!rows.isEmpty()) {
        if (state.equals(rows.get(0).state())) {
          return rows.get(0);
        }
        sagas.advance(rows.get(0).sagaId());
      }
      Thread.sleep(200);
    }
    throw new AssertionError("사가 상태 " + state + " 안 됨: " + sagas.byOrder(o));
  }

  static String order() {
    return "ORD-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void cancelOutOfStockOnlyRunsAllStepsAndRequestsPartialRefund() throws Exception {
    String o = order();
    ledger(o);
    participantsAccept(o);
    shortage(o, "CANCEL_OOS_ONLY", 1);
    ShortageRow s = await(o, "COMPLETED");
    assertThat(s.claimId()).isEqualTo("CLM-" + o);
    // 단계마다 사가 키, 수량 1
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/work-orders/FUL-" + o + "/shortage-resolutions"))
            .withHeader("Idempotency-Key", WireMock.equalTo(s.sagaId() + ":RESOLVE_WORK"))
            .withRequestBody(matchingJsonPath("$.qty", WireMock.equalTo("1"))));
    parts.verify(
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/shortfall-releases")));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("SHORTAGE"))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", WireMock.equalTo("1"))));
    // 같은 사건 재전달 → 사가 하나
    assertThat(sagas.byOrder(o)).hasSize(1);
  }

  @Test
  void cancelAllAndWholeOrderShortageAreEscalated() throws Exception {
    String o = order();
    shortage(o, "CANCEL_ALL", 1);
    ShortageRow s = await(o, "ESCALATED");
    assertThat(s.lastErrorCode()).isEqualTo("PICKED_CANCEL_NOT_SUPPORTED");
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds")));

    // 남은 수량 전부(5)가 결품 → 주문 확인 단계에서 상신, 작업·환불 호출 없음
    String o2 = order();
    ledger(o2);
    participantsAccept(o2);
    shortage(o2, "CANCEL_OOS_ONLY", 5);
    ShortageRow s2 = await(o2, "ESCALATED");
    assertThat(s2.lastErrorCode()).isEqualTo("WHOLE_ORDER_OUT_OF_STOCK");
    parts.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/work-orders/FUL-" + o2 + "/shortage-resolutions")));
  }
}
