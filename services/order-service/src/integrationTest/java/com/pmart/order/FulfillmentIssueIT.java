package com.pmart.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 이행 확인 필요(2026-11-08, 외부 검토 #5): ReservationBlocked → 주문에 확인 필요 표시(주문 상태·환불 없음) → 그 센터 운영자 목록·확인.
 * 같은 사건은 한 번.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=issue-test"
    })
@Import(TestJwtConfiguration.class)
class FulfillmentIssueIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  JsonNode get(String token, String path) {
    return mapper.readTree(
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().body(String.class));
  }

  @Test
  void blockedReservationsAreFlaggedForStaffWithoutTouchingTheOrder() throws Exception {
    String mfc = "MFC-FI-" + UUID.randomUUID().toString().substring(0, 6);
    String orderId = "ORD-FI-" + UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reservationId", "RSV-" + orderId);
    payload.put("orderId", orderId);
    payload.put("mfcId", mfc);
    payload.put("sku", "E-00000007");
    payload.put("lot", "LOT-1");
    payload.put("blockedQty", 2);
    payload.put("reason", "QUALITY_HOLD");
    payload.put("blockedAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("ReservationBlocked")
            .from("mfc-inventory-service")
            .aggregate("Reservation", "RSV-" + orderId, 3)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.inventory.v1", "RSV-" + orderId, json).join();
    kafka.send("mfc.inventory.v1", "RSV-" + orderId, json).join(); // 같은 사건 재전달

    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    Integer n = 0;
    while (Instant.now().isBefore(until)) {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM order_fulfillment_issue WHERE order_id = ?",
              Integer.class,
              orderId);
      if (n != null && n > 0) {
        break;
      }
      Thread.sleep(200);
    }
    Thread.sleep(1000);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_fulfillment_issue WHERE order_id = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);

    // 다른 센터 운영자·고객은 못 본다
    String other = TestJwt.operator("op-x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    assertThat(
            http.get()
                .uri("/fulfillment-issues?mfcId=" + mfc)
                .headers(h -> h.setBearerAuth(other))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value())
        .isEqualTo(403);
    String op = TestJwt.operator("op-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    JsonNode items = get(op, "/fulfillment-issues?mfcId=" + mfc).path("items");
    assertThat(items).hasSize(1);
    JsonNode issue = items.path(0);
    assertThat(issue.path("orderId").asString()).isEqualTo(orderId);
    assertThat(issue.path("reason").asString()).isEqualTo("QUALITY_HOLD");
    assertThat(issue.path("state").asString()).isEqualTo("OPEN");

    // 확인 → ACKNOWLEDGED, OPEN 목록에서 빠짐
    var e =
        http.post()
            .uri("/fulfillment-issues/" + issue.path("issueId").asString() + "/acknowledgements")
            .headers(
                h -> {
                  h.setBearerAuth(op);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"note\":\"고객에게 전화 — 다른 상품으로 대체 동의\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    assertThat(mapper.readTree(e.getBody()).path("state").asString()).isEqualTo("ACKNOWLEDGED");
    assertThat(get(op, "/fulfillment-issues?mfcId=" + mfc).path("items")).isEmpty();
    assertThat(get(op, "/fulfillment-issues?mfcId=" + mfc + "&state=ACKNOWLEDGED").path("items"))
        .hasSize(1);
  }

  /** 피킹 시작 전 재확보(#5 안 C): ReservationBlockCleared(남은 보류 0) → 확인 필요 자동 RESOLVED. */
  @Test
  void clearedBlockResolvesIssue() throws Exception {
    String mfc = "MFC-FC-" + UUID.randomUUID().toString().substring(0, 6);
    String orderId = "ORD-FC-" + UUID.randomUUID();
    String rid = "RSV-" + orderId;
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("reservationId", rid);
    b.put("orderId", orderId);
    b.put("mfcId", mfc);
    b.put("sku", "E-00000008");
    b.put("lot", "LOT-2");
    b.put("blockedQty", 1);
    b.put("reason", "QUALITY_HOLD");
    kafka
        .send(
            "mfc.inventory.v1",
            rid,
            EventEnvelopeFixtures.envelope("ReservationBlocked")
                .from("mfc-inventory-service")
                .aggregate("Reservation", rid, 3)
                .payload(b)
                .buildJson())
        .join();
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("reservationId", rid);
    c.put("orderId", orderId);
    c.put("mfcId", mfc);
    c.put("sku", "E-00000008");
    c.put("lot", "LOT-2");
    c.put("resecuredQty", 1);
    c.put("remainingBlockedQty", 0);
    kafka
        .send(
            "mfc.inventory.v1",
            rid,
            EventEnvelopeFixtures.envelope("ReservationBlockCleared")
                .from("mfc-inventory-service")
                .aggregate("Reservation", rid, 4)
                .payload(c)
                .buildJson())
        .join();
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    String state = null;
    while (Instant.now().isBefore(until)) {
      var rows =
          jdbc.queryForList(
              "SELECT state FROM order_fulfillment_issue WHERE order_id = ?",
              String.class,
              orderId);
      state = rows.isEmpty() ? null : rows.get(0);
      if ("RESOLVED".equals(state)) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(state).isEqualTo("RESOLVED");
    String op = TestJwt.operator("op-2", List.of("MFC_SUPERVISOR"), List.of(mfc));
    JsonNode items = get(op, "/fulfillment-issues?mfcId=" + mfc + "&state=RESOLVED").path("items");
    assertThat(items).hasSize(1);
    assertThat(items.path(0).path("resolvedAt").isNull()).isFalse();
    assertThat(items.path(0).path("note").asString()).contains("재확보 1개");
  }
}
