package com.pmart.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * 결제 흐름(결제 명세 §11): 결제 시작(내부) → 고객 승인 요청 → 토스 승인(WireMock) → 승인·불일치·불명·만료, 취소(부분·한도·불명·보상), 웹훅. 실제
 * PostgreSQL·Kafka, 토스는 WireMock(D5).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.payment.reconcile-interval=PT1H",
      "pmart.payment.toss.secret-key=test_gsk_docs_it",
      "pmart.payment.toss.client-key=test_gck_docs_it",
      "pmart.payment.toss.read-timeout=PT5S",
      "pmart.payment.toss.webhook-secret=it-webhook-secret"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "toss", baseUrlProperties = "pmart.payment.toss.base-url"))
class PaymentFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("toss")
  WireMockServer toss;

  static final String ORCH = TestJwt.service("order-orchestration-service");
  static final String CLAIM = TestJwt.service("claim-service");

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, Object body) {
    return post(token, path, body, UUID.randomUUID().toString());
  }

  Res post(String token, String path, Object body, String key) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "it");
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

  /** 결제 시작 → orderId(= 결제 시도 ID). */
  String start(String customer, long amount, Instant expires) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", "ORD-" + UUID.randomUUID());
    b.put("sagaId", "SAGA-" + UUID.randomUUID());
    b.put("amount", amount);
    b.put("currency", "KRW");
    b.put("method", "TOSS_PAYMENTS");
    b.put("customerRef", customer);
    b.put("orderName", "생수 외 2건");
    b.put("resourceExpiresAt", expires.toString());
    Res r = post(ORCH, "/internal/payments", b);
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().path("checkout").path("clientKey").asString())
        .isEqualTo("test_gck_docs_it");
    return r.body().path("checkout").path("pgOrderId").asString();
  }

  static String tossPayment(
      String paymentKey, String orderId, long total, long balance, String status, String cancels) {
    return """
        {"paymentKey":"%s","orderId":"%s","status":"%s","totalAmount":%d,"balanceAmount":%d,
         "currency":"KRW","method":"카드","approvedAt":"2026-10-30T19:00:00+09:00",
         "isPartialCancelable":true,"cancels":%s}"""
        .formatted(paymentKey, orderId, status, total, balance, cancels);
  }

  void stubConfirm(String paymentKey, int http, String body) {
    toss.stubFor(
        WireMock.post(urlPathEqualTo("/v1/payments/confirm"))
            .withRequestBody(matchingJsonPath("$.paymentKey", equalTo(paymentKey)))
            .willReturn(
                aResponse()
                    .withStatus(http)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  void stubGet(String paymentKey, String body) {
    toss.stubFor(
        WireMock.get(urlPathEqualTo("/v1/payments/" + paymentKey))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  Res confirm(String customer, String orderId, String paymentKey, long amount) {
    return post(
        TestJwt.customer(customer),
        "/payment-confirmations",
        Map.of("orderId", orderId, "paymentKey", paymentKey, "amount", amount));
  }

  int confirmCalls(String paymentKey) {
    return toss.findAll(
            postRequestedFor(urlPathEqualTo("/v1/payments/confirm"))
                .withRequestBody(matchingJsonPath("$.paymentKey", equalTo(paymentKey))))
        .size();
  }

  int events(String type, String attemptId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ? AND topic = 'order.payment.v1'",
            Integer.class,
            type,
            attemptId);
    return n == null ? 0 : n;
  }

  @Test
  void approvalWithIdChecksAndDuplicateRequests() {
    String cu = "CU-" + UUID.randomUUID();
    String orderId = start(cu, 27100, Instant.now().plusSeconds(600));
    String pk = "tgen_" + UUID.randomUUID();
    stubConfirm(pk, 200, tossPayment(pk, orderId, 27100, 27100, "DONE", "[]"));

    // P-2 금액 변조: 토스 호출 없음
    Res tampered = confirm(cu, orderId, pk, 27101);
    assertThat(tampered.status()).isEqualTo(400);
    assertThat(tampered.body().path("code").asString()).isEqualTo("AMOUNT_MISMATCH");
    // 다른 고객의 결제 시도는 없는 것과 같다
    assertThat(confirm("CU-other", orderId, pk, 27100).status()).isEqualTo(404);
    // ID-4 없는 orderId
    assertThat(confirm(cu, "pay-unknown000000", pk, 27100).status()).isEqualTo(404);
    assertThat(confirmCalls(pk)).isZero();

    // P-1 정상: 토스 승인 요청은 저장된 값 그대로(시도별 멱등키)
    Res ok = confirm(cu, orderId, pk, 27100);
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body().path("stage").asString()).isEqualTo("APPROVED");
    assertThat(ok.body().path("state").asString()).isEqualTo("APPROVED");
    assertThat(ok.body().path("orderId").asString()).isEqualTo(orderId);
    String idemKey =
        jdbc.queryForObject(
            "SELECT confirm_idempotency_key FROM payment_attempt WHERE payment_attempt_id = ?",
            String.class,
            orderId);
    toss.verify(
        postRequestedFor(urlPathEqualTo("/v1/payments/confirm"))
            .withHeader("Idempotency-Key", equalTo(idemKey))
            .withHeader(
                "Authorization",
                equalTo(
                    "Basic " + Base64.getEncoder().encodeToString("test_gsk_docs_it:".getBytes())))
            .withRequestBody(
                equalToJson(
                    "{\"paymentKey\":\""
                        + pk
                        + "\",\"orderId\":\""
                        + orderId
                        + "\",\"amount\":27100}")));
    assertThat(events("PaymentApproved", orderId)).isEqualTo(1);

    // P-3 중복 승인 요청: 기존 결과, 토스 호출 1회
    assertThat(confirm(cu, orderId, pk, 27100).body().path("stage").asString())
        .isEqualTo("APPROVED");
    assertThat(confirmCalls(pk)).isEqualTo(1);
    // ID-5 다른 paymentKey
    Res otherKey = confirm(cu, orderId, "tgen_other", 27100);
    assertThat(otherKey.status()).isEqualTo(409);
    assertThat(otherKey.body().path("code").asString()).isEqualTo("PAYMENT_KEY_MISMATCH");

    // 고객 상태 조회·내부 조회
    assertThat(get(TestJwt.customer(cu), "/payments/" + orderId).body().path("stage").asString())
        .isEqualTo("APPROVED");
    Res internal = get(ORCH, "/internal/payments/" + orderId);
    assertThat(internal.body().path("pgTransactionId").asString()).isEqualTo(pk);
    assertThat(internal.body().path("limits").path("remainingLimit").asInt()).isEqualTo(27100);

    // 결제 시작 재전송(같은 키)은 같은 orderId — 다른 주문으로 같은 키면 409
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", "ORD-X");
    b.put("sagaId", "S");
    b.put("amount", 1000);
    b.put("currency", "KRW");
    b.put("method", "TOSS_PAYMENTS");
    b.put("customerRef", cu);
    b.put("resourceExpiresAt", Instant.now().plusSeconds(600).toString());
    String key = UUID.randomUUID().toString();
    String first =
        post(ORCH, "/internal/payments", b, key).body().path("paymentAttemptId").asString();
    assertThat(post(ORCH, "/internal/payments", b, key).body().path("paymentAttemptId").asString())
        .isEqualTo(first);
    b.put("amount", 2000);
    assertThat(post(ORCH, "/internal/payments", b, key).status()).isEqualTo(409);
  }

  @Test
  void mismatchUnknownExpiryAndUserCancel() {
    String cu = "CU-" + UUID.randomUUID();

    // P-6 승인 응답 status != DONE → MISMATCH_HOLD
    String o1 = start(cu, 5000, Instant.now().plusSeconds(600));
    String pk1 = "tgen_" + UUID.randomUUID();
    stubConfirm(pk1, 200, tossPayment(pk1, o1, 5000, 5000, "WAITING_FOR_DEPOSIT", "[]"));
    assertThat(confirm(cu, o1, pk1, 5000).body().path("stage").asString())
        .isEqualTo("MISMATCH_HOLD");
    assertThat(events("PaymentMismatch", o1)).isEqualTo(1);

    // ID-6 응답 orderId 가 다름 → MISMATCH_HOLD
    String o2 = start(cu, 5000, Instant.now().plusSeconds(600));
    String pk2 = "tgen_" + UUID.randomUUID();
    stubConfirm(pk2, 200, tossPayment(pk2, "pay-someoneelse0", 5000, 5000, "DONE", "[]"));
    assertThat(confirm(cu, o2, pk2, 5000).body().path("stage").asString())
        .isEqualTo("MISMATCH_HOLD");

    // P-5 승인 결과 불명(5xx) → CONFIRM_UNKNOWN → 원거래 조회로 APPROVED
    String o3Order = "ORD-" + UUID.randomUUID();
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", o3Order);
    b.put("sagaId", "S");
    b.put("amount", 7000);
    b.put("currency", "KRW");
    b.put("method", "TOSS_PAYMENTS");
    b.put("customerRef", cu);
    b.put("resourceExpiresAt", Instant.now().plusSeconds(600).toString());
    String o3 = post(ORCH, "/internal/payments", b).body().path("paymentAttemptId").asString();
    String pk3 = "tgen_" + UUID.randomUUID();
    stubConfirm(pk3, 500, "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"x\"}");
    Res unknown = confirm(cu, o3, pk3, 7000);
    assertThat(unknown.body().path("stage").asString()).isEqualTo("CONFIRM_UNKNOWN");
    assertThat(unknown.body().path("state").asString()).isEqualTo("UNKNOWN");
    assertThat(events("PaymentResultUnknown", o3)).isEqualTo(1);
    // ID-7 같은 주문에 불명 결제가 있으면 새 결제 시작 거절
    Res blocked = post(ORCH, "/internal/payments", b);
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.body().path("code").asString()).isEqualTo("PAYMENT_IN_PROGRESS");
    // 원거래 조회: 토스는 DONE
    stubGet(pk3, tossPayment(pk3, o3, 7000, 7000, "DONE", "[]"));
    Res inq =
        post(ORCH, "/internal/payments/" + o3 + "/inquiries", Map.of("reason", "RESULT_UNKNOWN"));
    assertThat(inq.status()).isEqualTo(200);
    assertThat(inq.body().path("resultingState").asString()).isEqualTo("APPROVED");
    assertThat(events("PaymentApproved", o3)).isEqualTo(1);

    // P-4 예약 만료 뒤 승인 요청: 토스 호출 없이 EXPIRED
    String o4 = start(cu, 3000, Instant.now().plusSeconds(1));
    String pk4 = "tgen_" + UUID.randomUUID();
    sleep(1500);
    Res expired = confirm(cu, o4, pk4, 3000);
    assertThat(expired.status()).isEqualTo(422);
    assertThat(expired.body().path("code").asString()).isEqualTo("RESERVATION_EXPIRED");
    assertThat(confirmCalls(pk4)).isZero();
    assertThat(get(TestJwt.customer(cu), "/payments/" + o4).body().path("stage").asString())
        .isEqualTo("EXPIRED");

    // ID-3 사용자 취소 보고(앱 보관 orderId)
    String o5 = start(cu, 3000, Instant.now().plusSeconds(600));
    Res canceled =
        post(
            TestJwt.customer(cu),
            "/payment-authorization-failures",
            Map.of("orderId", o5, "errorCode", "PAY_PROCESS_CANCELED", "errorMessage", "사용자 취소"));
    assertThat(canceled.body().path("stage").asString()).isEqualTo("CANCELED_BY_USER");
    assertThat(canceled.body().path("failure").path("code").asString())
        .isEqualTo("PAY_PROCESS_CANCELED");
    assertThat(events("PaymentFailed", o5)).isEqualTo(1);
  }

  @Test
  void refundsLimitsUnknownAndCompensation() {
    String cu = "CU-" + UUID.randomUUID();
    String o = start(cu, 27100, Instant.now().plusSeconds(600));
    String pk = "tgen_" + UUID.randomUUID();
    stubConfirm(pk, 200, tossPayment(pk, o, 27100, 27100, "DONE", "[]"));
    confirm(cu, o, pk, 27100);

    // C-2 한도 초과: 토스 호출 없음
    Res over =
        post(
            CLAIM,
            "/internal/payments/" + o + "/cancellations",
            refund(o + "-over", 30000, "PARTIAL"));
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("REFUND_LIMIT_EXCEEDED");

    // C-1 OMS §10.2: 27,100 → B 9,000 부분 취소 → 잔액 18,100
    toss.stubFor(
        WireMock.post(urlPathEqualTo("/v1/payments/" + pk + "/cancel"))
            .withRequestBody(matchingJsonPath("$.cancelAmount", equalTo("9000")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        tossPayment(
                            pk,
                            o,
                            27100,
                            18100,
                            "PARTIAL_CANCELED",
                            "[{\"transactionKey\":\"tx-9000\",\"cancelAmount\":9000,\"cancelStatus\":\"DONE\",\"canceledAt\":\"2026-10-30T19:10:00+09:00\"}]"))));
    String key = UUID.randomUUID().toString();
    Res part =
        post(
            CLAIM,
            "/internal/payments/" + o + "/cancellations",
            refund(o + "-9000", 9000, "PARTIAL"),
            key);
    assertThat(part.status()).isEqualTo(202);
    assertThat(part.body().path("status").asString()).isEqualTo("APPLIED");
    toss.verify(
        postRequestedFor(urlPathEqualTo("/v1/payments/" + pk + "/cancel"))
            .withHeader("Idempotency-Key", equalTo(key))
            .withRequestBody(matchingJsonPath("$.refundableAmount", equalTo("27100"))));
    Res after = get(ORCH, "/internal/payments/" + o);
    assertThat(after.body().path("stage").asString()).isEqualTo("PARTIAL_CANCELED");
    assertThat(after.body().path("balanceAmount").asInt()).isEqualTo(18100);
    assertThat(after.body().path("limits").path("remainingLimit").asInt()).isEqualTo(18100);
    assertThat(
            get(CLAIM, "/internal/payments/" + o + "/cancellations/" + key)
                .body()
                .path("pgCancelTransactionId")
                .asString())
        .isEqualTo("tx-9000");
    // 같은 키 재전송은 기존 결과, 같은 환불 의도 재사용은 거절
    assertThat(
            post(
                    CLAIM,
                    "/internal/payments/" + o + "/cancellations",
                    refund(o + "-9000", 9000, "PARTIAL"),
                    key)
                .body()
                .path("status")
                .asString())
        .isEqualTo("APPLIED");
    assertThat(
            post(
                    CLAIM,
                    "/internal/payments/" + o + "/cancellations",
                    refund(o + "-9000", 9000, "PARTIAL"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("REFUND_INTENT_ALREADY_USED");

    // C-3 취소 결과 불명 → 원거래 조회로 성공 반영(이중 환불 없음)
    toss.stubFor(
        WireMock.post(urlPathEqualTo("/v1/payments/" + pk + "/cancel"))
            .withRequestBody(matchingJsonPath("$.cancelAmount", equalTo("1000")))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withBody("{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\"}")));
    String k2 = UUID.randomUUID().toString();
    Res unk =
        post(
            CLAIM,
            "/internal/payments/" + o + "/cancellations",
            refund(o + "-1000", 1000, "PARTIAL"),
            k2);
    assertThat(unk.body().path("status").asString()).isEqualTo("UNKNOWN_RESULT");
    assertThat(
            get(ORCH, "/internal/payments/" + o)
                .body()
                .path("limits")
                .path("unknownHeldAmount")
                .asInt())
        .isEqualTo(1000);
    stubGet(
        pk,
        tossPayment(
            pk,
            o,
            27100,
            17100,
            "PARTIAL_CANCELED",
            "[{\"transactionKey\":\"tx-9000\",\"cancelAmount\":9000,\"cancelStatus\":\"DONE\"},"
                + "{\"transactionKey\":\"tx-1000\",\"cancelAmount\":1000,\"cancelStatus\":\"DONE\"}]"));
    post(ORCH, "/internal/payments/" + o + "/inquiries", Map.of("reason", "RESULT_UNKNOWN"));
    Res resolved = get(CLAIM, "/internal/payments/" + o + "/cancellations/" + k2);
    assertThat(resolved.body().path("state").asString()).isEqualTo("SUCCEEDED");
    assertThat(resolved.body().path("pgCancelTransactionId").asString()).isEqualTo("tx-1000");
    assertThat(
            get(ORCH, "/internal/payments/" + o)
                .body()
                .path("limits")
                .path("remainingLimit")
                .asInt())
        .isEqualTo(17100);

    // 보상 취소는 부분 취소 누적이 있으면 거절
    Res noComp =
        post(
            ORCH,
            "/internal/payments/" + o + "/compensation-cancellations",
            Map.of("sagaId", "S", "step", "CONFIRM_ORDER", "reason", "ORDER_CONFIRM_FAILED"));
    assertThat(noComp.status()).isEqualTo(409);

    // C-5 다른 결제: 승인 후 주문 확정 실패 → 보상 전액 취소
    String o2 = start(cu, 15000, Instant.now().plusSeconds(600));
    String pk2 = "tgen_" + UUID.randomUUID();
    stubConfirm(pk2, 200, tossPayment(pk2, o2, 15000, 15000, "DONE", "[]"));
    confirm(cu, o2, pk2, 15000);
    toss.stubFor(
        WireMock.post(urlPathEqualTo("/v1/payments/" + pk2 + "/cancel"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        tossPayment(
                            pk2,
                            o2,
                            15000,
                            0,
                            "CANCELED",
                            "[{\"transactionKey\":\"tx-all\",\"cancelAmount\":15000,\"cancelStatus\":\"DONE\"}]"))));
    Res comp =
        post(
            ORCH,
            "/internal/payments/" + o2 + "/compensation-cancellations",
            Map.of("sagaId", "S", "step", "CONFIRM_ORDER", "reason", "ORDER_CONFIRM_FAILED"));
    assertThat(comp.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(get(ORCH, "/internal/payments/" + o2).body().path("stage").asString())
        .isEqualTo("CANCELED");
    assertThat(events("CompensationCancelResult", o2)).isEqualTo(1);
  }

  @Test
  void webhookRequiresSignatureAndIsDeduplicated() throws Exception {
    String body =
        "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"createdAt\":\"2026-10-30T10:00:00Z\",\"data\":{\"orderId\":\"pay-none000000\",\"status\":\"DONE\"}}";
    assertThat(webhook(body, "bad").status()).isEqualTo(401);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("it-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String sig =
        Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    assertThat(webhook(body, sig).status()).isEqualTo(200);
    assertThat(webhook(body, sig).status()).isEqualTo(200);
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM toss_webhook_event WHERE event_type = 'PAYMENT_STATUS_CHANGED'",
            Integer.class);
    assertThat(n).isEqualTo(1);
  }

  Res webhook(String body, String signature) {
    var e =
        http.post()
            .uri("/webhooks/toss")
            .header("Toss-Signature", signature)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> refund(String intent, long amount, String scope) {
    return Map.of(
        "refundIntentId", intent,
        "claimId", "CL-1",
        "amount", amount,
        "scope", scope,
        "reason", "SHORTAGE");
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 결제위젯 설정(BR-34): 회원만, clientKey = 설정값, customerKey = 본인 accountId, 세션 재사용용 private 캐시. */
  @Test
  void widgetConfigForMembersOnly() {
    var e =
        http.get()
            .uri("/payment-widget-config")
            .headers(h -> h.setBearerAuth(TestJwt.customer("CU-widget-1")))
            .retrieve()
            .toEntity(String.class);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    JsonNode b = mapper.readTree(e.getBody());
    assertThat(b.path("provider").asString()).isEqualTo("TOSS_PAYMENTS");
    assertThat(b.path("clientKey").asString()).isEqualTo("test_gck_docs_it");
    assertThat(b.path("customerKey").asString()).isEqualTo("CU-widget-1");
    assertThat(e.getHeaders().getCacheControl()).contains("private").contains("max-age=3600");
    // 회원이 아닌 토큰(운영자·서비스)은 403 — 비회원 결제 없음(OMS §4)
    assertThat(
            get(
                    TestJwt.operator("OP-1", java.util.List.of("CS_AGENT"), java.util.List.of()),
                    "/payment-widget-config")
                .status())
        .isEqualTo(403);
    assertThat(get(ORCH, "/payment-widget-config").status()).isEqualTo(403);
  }
}
