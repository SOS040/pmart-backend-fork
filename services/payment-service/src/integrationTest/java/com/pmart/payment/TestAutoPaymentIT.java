package com.pmart.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * [LAN 시험 전용] 시험 결제 자동 승인(0.2.0, 2026-11-19 MFC 시뮬레이터 R-21): 설정을 켜면 {@code TEST_AUTO_<orderId>} 키는
 * 토스를 부르지 않고 승인(금액 검사·PaymentApproved 는 실제와 같음)·취소된다. 다른 orderId 의 시험 키는 거절, 금액 변조는 실제와 같이 400.
 * 토스(WireMock)는 한 번도 불리지 않는다.
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
      "pmart.payment.toss.webhook-secret=it-webhook-secret",
      "pmart.payment.test-auto.enabled=true"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "toss", baseUrlProperties = "pmart.payment.toss.base-url"))
class TestAutoPaymentIT extends PostgresKafkaContainerSupport {

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
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  String start(String customer, long amount) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", "ORD-" + UUID.randomUUID());
    b.put("sagaId", "SAGA-" + UUID.randomUUID());
    b.put("amount", amount);
    b.put("currency", "KRW");
    b.put("method", "TOSS_PAYMENTS");
    b.put("customerRef", customer);
    b.put("orderName", "시험 주문");
    b.put("resourceExpiresAt", Instant.now().plusSeconds(600).toString());
    Res r = post(ORCH, "/internal/payments", b);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("checkout").path("pgOrderId").asString();
  }

  Res confirm(String customer, String orderId, String paymentKey, long amount) {
    return post(
        TestJwt.customer(customer),
        "/payment-confirmations",
        Map.of("orderId", orderId, "paymentKey", paymentKey, "amount", amount));
  }

  int approvedEvents(String attemptId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'PaymentApproved' AND aggregateid = ?"
                + " AND topic = 'order.payment.v1'",
            Integer.class,
            attemptId);
    return n == null ? 0 : n;
  }

  @Test
  void testKeyApprovesAndRefundsWithoutToss() {
    String cu = "CU-" + UUID.randomUUID();
    String o = start(cu, 27100);
    String key = "TEST_AUTO_" + o;

    Res tampered = confirm(cu, o, key, 27101);
    assertThat(tampered.status()).as("금액 검사는 실제와 같다").isEqualTo(400);
    assertThat(tampered.body().path("code").asString()).isEqualTo("AMOUNT_MISMATCH");

    Res ok = confirm(cu, o, key, 27100);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("stage").asString()).isEqualTo("APPROVED");
    assertThat(approvedEvents(o)).isEqualTo(1);

    // 환불(부분)도 토스 없이
    Res refund =
        post(
            CLAIM,
            "/internal/payments/" + o + "/cancellations",
            Map.of(
                "refundIntentId", o + "-r1",
                "claimId", "CL-1",
                "amount", 9000,
                "scope", "PARTIAL",
                "reason", "SHORTAGE"));
    assertThat(refund.status()).as(refund.body().toString()).isIn(200, 202);
    assertThat(refund.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(
            jdbc.queryForObject(
                "SELECT balance_amount FROM payment_attempt WHERE payment_attempt_id = ?",
                Long.class,
                o))
        .isEqualTo(18100L);
    assertThat(toss.getAllServeEvents()).as("토스는 부르지 않는다").isEmpty();
  }

  @Test
  void testKeyForAnotherOrderIsRejected() {
    String cu = "CU-" + UUID.randomUUID();
    String o = start(cu, 15000);
    Res bad = confirm(cu, o, "TEST_AUTO_pay-someone-else", 15000);
    assertThat(bad.body().path("stage").asString()).isNotEqualTo("APPROVED");
    assertThat(approvedEvents(o)).isZero();
    assertThat(toss.getAllServeEvents()).isEmpty();
  }
}
