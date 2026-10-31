package com.pmart.claim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.claim.application.RefundService;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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
 * 고객 전체 취소: 접수(order 원장 심사) → ClaimOpened·CancellationRequested → OrderCancelled 사건으로 취소 확정·환불 의도 →
 * payment 취소(멱등키 고정) → RefundResult 로 환불 완료·클레임 종결. 인계됨이면 REJECTED, payment 미전달이면 재실행·원거래 조회로 잇는다.
 * order·payment·auth 는 한 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${deps.url}/api/v1/internal/service-tokens",
      "pmart.claim.order-base-url=${deps.url}",
      "pmart.claim.payment-base-url=${deps.url}",
      "pmart.claim.refund-sweep-interval=PT1H" // 시험이 재개를 직접 부른다
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "deps", baseUrlProperties = "deps.url"))
class CancelClaimFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired RefundService refunds;

  @InjectWireMock("deps")
  WireMockServer deps;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    deps.resetAll();
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "access_token",
                        TestJwt.service("claim-service"),
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

  /** order 원장: 상품 19,000(10개) + 봉투 100 + 배달팁 0 = 승인 19,100. */
  void order(String orderId, String accountId, String status) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("orderId", orderId);
    o.put("accountId", accountId);
    o.put("status", status);
    o.put("version", 1);
    o.put("mfcId", "MFC-1");
    o.put("paymentAttemptId", "PAY-" + orderId);
    o.put("approvedAmount", 19_100);
    o.put("itemAmount", 19_000);
    o.put("deliveryTip", 0);
    o.put("bagFee", 100);
    o.put("totalAmount", 19_100);
    o.put("firstPurchase", true);
    o.put("confirmedAt", "2026-10-31T01:00:00Z");
    o.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId", orderId + "-L1",
                "sku", "E-00000001",
                "quantity", 10,
                "unitPrice", 1_900,
                "lineAmount", 19_000,
                "originalQty", 10,
                "unfulfilledQty", 10,
                "handedOverQty", 0,
                "cancelledBeforeShipQty", 0)));
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(json(200, o)));
  }

  Res post(String token, String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/claims")
            .headers(
                h -> {
                  h.setBearerAuth(token);
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

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> cancel(String orderId, int qty) {
    return Map.of(
        "orderId",
        orderId,
        "type",
        "CANCEL",
        "reason",
        "CHANGE_OF_MIND",
        "channel",
        "CUSTOMER_APP",
        "lines",
        List.of(Map.of("orderLineId", orderId + "-L1", "qty", qty)));
  }

  void send(String topic, String type, String aggregateType, String id, Map<String, Object> p) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("it")
            .aggregate(aggregateType, id, 2)
            .payload(p)
            .buildJson();
    kafka.send(topic, id, json).join();
  }

  void orderCancelled(String orderId, String claimId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("orderVersion", 2);
    p.put("scope", "FULL");
    p.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 10)));
    p.put("reason", "CANCEL_BEFORE_SHIP");
    p.put("claimId", claimId);
    send("order.order.v1", "OrderCancelled", "Order", orderId, p);
  }

  Res await(String token, String path, Predicate<JsonNode> done) throws InterruptedException {
    Res r = get(token, path);
    for (int i = 0; i < 150 && !done.test(r.body()); i++) {
      Thread.sleep(100);
      r = get(token, path);
    }
    return r;
  }

  void paymentAccepts(String orderId) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .willReturn(
                json(202, Map.of("status", "ACCEPTED", "requestId", "x", "resultId", "CXL-1"))));
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  @Test
  void customerCancelsWholeOrderAndGetsRefund() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    paymentAccepts(orderId);

    // 남의 주문 404, 부분 취소 422, 반품 422(아직 없음)
    assertThat(
            post(TestJwt.customer("CU-OTHER"), UUID.randomUUID().toString(), cancel(orderId, 10))
                .status())
        .isEqualTo(404);
    Res partial = post(me, UUID.randomUUID().toString(), cancel(orderId, 5));
    assertThat(partial.status()).isEqualTo(422);
    assertThat(partial.body().path("code").asString()).isEqualTo("PARTIAL_CANCEL_NOT_ALLOWED");
    Map<String, Object> ret = new LinkedHashMap<>(cancel(orderId, 10));
    ret.put("type", "RETURN");
    Res returns = post(me, UUID.randomUUID().toString(), ret);
    assertThat(returns.status()).isEqualTo(422);
    assertThat(returns.body().path("code").asString()).isEqualTo("UNSUPPORTED_ACTION");

    // 접수 → STOP_PENDING, 같은 키 재생, 다른 키는 중복 접수
    String key = UUID.randomUUID().toString();
    Res opened = post(me, key, cancel(orderId, 10));
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    assertThat(opened.body().path("state").asString()).isEqualTo("STOP_PENDING");
    String stopId = opened.body().path("stopRequestId").asString();
    assertThat(stopId).startsWith("STOP-");
    assertThat(opened.body().path("lines").path(0).path("decision").asString())
        .isEqualTo("PENDING");
    assertThat(post(me, key, cancel(orderId, 10)).body().path("claimId").asString())
        .isEqualTo(claimId);
    Res dup = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(dup.status()).isEqualTo(422);
    assertThat(dup.body().path("code").asString()).isEqualTo("DUPLICATE_CLAIM");

    assertThat(outbox("ClaimOpened", claimId)).isEqualTo(1);
    assertThat(outbox("CancellationRequested", claimId)).isEqualTo(1);
    Map<String, Object> req =
        jdbc.queryForMap(
            "SELECT topic, payload::text AS payload FROM outbox_event"
                + " WHERE type = 'CancellationRequested' AND aggregateid = ?",
            claimId);
    assertThat(req.get("topic")).isEqualTo("order.claim.v1");
    JsonNode rp = mapper.readTree((String) req.get("payload")).path("payload");
    assertThat(rp.path("stopRequestId").asString()).isEqualTo(stopId);
    assertThat(rp.path("lines").path(0).path("qty").asInt()).isEqualTo(10);

    // 조회: 본인 200, 남 404, 목록
    assertThat(get(me, "/claims/" + claimId).status()).isEqualTo(200);
    assertThat(get(TestJwt.customer("CU-OTHER"), "/claims/" + claimId).status()).isEqualTo(404);
    assertThat(get(me, "/claims").body().path("items").findValuesAsString("claimId"))
        .contains(claimId);

    // 주문 취소 확정 → IN_PROGRESS, 환불 의도(전액) → payment 취소(멱등키 고정)
    orderCancelled(orderId, claimId);
    Res inProgress =
        await(
            me,
            "/claims/" + claimId,
            b -> "PG_PENDING".equals(b.path("refunds").path(0).path("state").asString()));
    assertThat(inProgress.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(inProgress.body().path("lines").path(0).path("approvedQty").asInt()).isEqualTo(10);
    JsonNode refund = inProgress.body().path("refunds").path(0);
    assertThat(refund.path("amount").asInt()).isEqualTo(19_100);
    assertThat(refund.path("breakdown").path("bagFee").asInt()).isEqualTo(100);
    String intentId = refund.path("refundIntentId").asString();
    deps.verify(
        1,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withHeader("Idempotency-Key", WireMock.equalTo("REFUND-" + intentId))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("19100")))
            .withRequestBody(matchingJsonPath("$.scope", WireMock.equalTo("FULL")))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("CANCEL_BEFORE_SHIP"))));
    assertThat(outbox("RefundIntentCreated", claimId)).isEqualTo(1);

    // PG 결과 성공 → 환불 완료·클레임 종결
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("refundRequestId", "REFUND-" + intentId);
    result.put("refundIntentId", intentId);
    result.put("paymentAttemptId", "PAY-" + orderId);
    result.put("pgTransactionId", "tcxl_1");
    result.put("amount", 19_100);
    result.put("result", "SUCCESS");
    send("order.payment.v1", "RefundResult", "PaymentAttempt", "PAY-" + orderId, result);
    Res closed = await(me, "/claims/" + claimId, b -> "CLOSED".equals(b.path("state").asString()));
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    Res refundList = get(me, "/claims/" + claimId + "/refunds");
    assertThat(refundList.status()).isEqualTo(200);
    assertThat(refundList.body().path("items").path(0).path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(refundList.body().path("items").path(0).path("pgResult").asString())
        .isEqualTo("SUCCESS");
    assertThat(outbox("ClaimClosed", claimId)).isEqualTo(1);

    // 종결 뒤에는 다시 취소 접수를 받지 않는다(주문이 CANCELLED)
    order(orderId, account, "CANCELLED");
    Res again = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(again.status()).isEqualTo(422);
    assertThat(again.body().path("code").asString()).isEqualTo("ORDER_NOT_CANCELLABLE");
  }

  @Test
  void paymentUnreachableThenResumeAndInquire() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .willReturn(aResponse().withStatus(503)));

    String claimId =
        post(me, UUID.randomUUID().toString(), cancel(orderId, 10))
            .body()
            .path("claimId")
            .asString();
    orderCancelled(orderId, claimId);
    Res pending =
        await(
            me,
            "/claims/" + claimId,
            b ->
                b.path("refunds").size() == 1
                    && !b.path("refunds").path(0).path("requestedAt").isMissingNode()
                    && !b.path("refunds").path(0).path("requestedAt").isNull());
    JsonNode r = pending.body().path("refunds").path(0);
    assertThat(r.path("state").asString()).isEqualTo("PENDING_EXECUTION");
    String intentId = r.path("refundIntentId").asString();

    // payment 복구 → 재실행(같은 키) → PG 결과 대기 → 원거래 조회 SUCCEEDED → 완료
    paymentAccepts(orderId);
    refunds.resume(intentId);
    assertThat(
            get(me, "/claims/" + claimId).body().path("refunds").path(0).path("state").asString())
        .isEqualTo("PG_PENDING");
    deps.stubFor(
        WireMock.get(
                urlPathEqualTo(
                    "/api/v1/internal/payments/PAY-"
                        + orderId
                        + "/cancellations/REFUND-"
                        + intentId))
            .willReturn(
                json(200, Map.of("requestId", "REFUND-" + intentId, "state", "SUCCEEDED"))));
    refunds.resume(intentId);
    JsonNode done = get(me, "/claims/" + claimId).body();
    assertThat(done.path("refunds").path(0).path("state").asString()).isEqualTo("COMPLETED");
    assertThat(done.path("state").asString()).isEqualTo("CLOSED");
    // 미전달(503)은 공용 클라이언트 재시도까지 포함해 여러 번일 수 있다 — 모두 같은 멱등키(새 금액 취소 없음)
    deps.verify(
        WireMock.moreThanOrExactly(2),
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withHeader("Idempotency-Key", WireMock.equalTo("REFUND-" + intentId)));
    var all =
        deps.findAll(
            postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations")));
    assertThat(all).allMatch(q -> ("REFUND-" + intentId).equals(q.getHeader("Idempotency-Key")));
  }

  @Test
  void handedOverRejectsClaim() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    Res opened = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    String claimId = opened.body().path("claimId").asString();

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("stopRequestId", opened.body().path("stopRequestId").asString());
    p.put("perPackage", List.of(Map.of("packageId", "PKG-1", "state", "HANDED_OVER")));
    send("mfc.fulfillment.v1", "WorkStopResult", "Fulfillment", orderId, p);
    Res rejected =
        await(me, "/claims/" + claimId, b -> "REJECTED".equals(b.path("state").asString()));
    assertThat(rejected.body().path("state").asString()).isEqualTo("REJECTED");
    assertThat(rejected.body().path("lines").path(0).path("rejectReason").asString())
        .isEqualTo("HANDED_OVER");
    // 거절 뒤에는 새 취소 접수를 다시 받을 수 있다(중복 접수 제한은 진행 중인 것만)
    assertThat(post(me, UUID.randomUUID().toString(), cancel(orderId, 10)).status()).isEqualTo(201);
  }
}
