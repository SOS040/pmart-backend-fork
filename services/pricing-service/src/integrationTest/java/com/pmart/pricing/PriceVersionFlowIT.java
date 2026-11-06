package com.pmart.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가격 흐름: 상품 SKU 투영 → PRICE 승인(ApprovalDecided 를 Kafka 로 흘림) → 즉시 발효·예약 발효(PriceVersionEffective
 * Outbox) → 조회·이력 → 견적(멱등·재조회·미설정 거절) → 반영 명령(승인 범위 대조). 실제 PostgreSQL·Kafka, 시험 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.pricing.activation-interval=PT1S"
    })
@Import(TestJwtConfiguration.class)
class PriceVersionFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String ORDER = TestJwt.service("order-service");

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

  Res post(String token, String key, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
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

  Res await(String path, Predicate<Res> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    Res r = get(MD, path);
    while (!done.test(r) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      r = get(MD, path);
    }
    return r;
  }

  void productPublished(String sku) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", "P-" + sku);
    payload.put("baseProductId", "BP-" + sku);
    payload.put("version", 1);
    payload.put("skus", List.of(Map.of("sku", sku, "state", "ACTIVE")));
    payload.put("effectiveAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("ProductVersionPublished")
            .from("product-service")
            .aggregate("Product", "P-" + sku, 1)
            .payload(payload)
            .buildJson();
    kafka.send("commerce.product.v1", "P-" + sku, json).join();
  }

  /** approval-service 가 내는 ApprovalDecided(PRICE) 를 흉내 낸다. */
  String priceApproved(String decision, List<Map<String, Object>> rows) {
    String approvalId = "AP-" + UUID.randomUUID();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineId", row.get("sku"));
      line.put("amount", row.get("basePrice"));
      line.put("decision", decision);
      line.put("targetRef", row.get("sku"));
      line.put("attributes", row);
      lines.add(line);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + UUID.randomUUID());
    payload.put("type", "PRICE");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType", "Price", "targetId", "PRICE-CHANGE", "targetService", "pricing-service"));
    payload.put("lines", lines);
    payload.put("approverId", "price-lead-1");
    payload.put("dossierVersion", 2);
    payload.put("decidedAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval.v1", approvalId, json).join();
    return approvalId;
  }

  void awaitApproval(String approvalId) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM price_approval WHERE approval_id = ?",
              Integer.class,
              approvalId);
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("approval " + approvalId + " not applied");
  }

  int effectiveEvents(String sku) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'PriceVersionEffective'"
                + " AND aggregateid = ? AND topic = 'commerce.pricing.v1'",
            Integer.class,
            sku);
    return n == null ? 0 : n;
  }

  /** 반영 회신(ADR-0039) Outbox 기록. 없으면 null. */
  JsonNode receipt(String approvalId) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'ApplicationReceiptRecorded'"
                + " AND aggregateid = ? AND topic = 'platform.approval-receipt.v1'",
            String.class,
            approvalId);
    assertThat(rows).hasSizeLessThanOrEqualTo(1);
    return rows.isEmpty() ? null : mapper.readTree(rows.get(0)).path("payload");
  }

  static Map<String, Object> row(String sku, long price, Instant effectiveFrom) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", sku);
    m.put("basePrice", price);
    if (effectiveFrom != null) {
      m.put("effectiveFrom", effectiveFrom.toString());
    }
    return m;
  }

  @Test
  void approvedPriceBecomesEffectiveAndQuotable() throws Exception {
    String sku = "E-1" + String.format("%07d", (int) (Math.random() * 9_999_999));
    String unknown = "E-9" + String.format("%07d", (int) (Math.random() * 9_999_999));

    // 가격 없음 = 0원이 아니라 PRICE_NOT_SET
    assertThat(get(MD, "/prices/" + sku).status()).isEqualTo(404);

    productPublished(sku);
    // SKU 투영이 먼저 와야 승인이 반영된다
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sku_projection WHERE sku = ?", Integer.class, sku);
      if (n != null && n > 0) {
        break;
      }
      Thread.sleep(200);
    }

    // 1) 즉시 발효(효력 시각 없음) + 무효 SKU·0원 행은 그 행만 거절
    String ap1 =
        priceApproved(
            "APPROVED",
            List.of(row(sku, 4500, null), row(unknown, 1000, null), row("E-00000000", 0, null)));
    awaitApproval(ap1);
    Res price = get(MD, "/prices/" + sku);
    assertThat(price.status()).isEqualTo(200);
    assertThat(price.body().path("basePrice").asInt()).isEqualTo(4500);
    assertThat(price.body().path("priceVersion").asInt()).isEqualTo(1);
    assertThat(effectiveEvents(sku)).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT reason_code FROM price_approval_line WHERE approval_id = ? AND state ="
                    + " 'REJECTED' ORDER BY reason_code",
                String.class,
                ap1))
        .containsExactly("SKU_NOT_FOUND", "SKU_NOT_FOUND");
    assertThat(
            jdbc.queryForObject(
                "SELECT result FROM price_approval WHERE approval_id = ?", String.class, ap1))
        .isEqualTo("PARTIAL");
    // 반영 회신: 같은 트랜잭션에서 Outbox — 전체 PARTIAL, 행별 결과·사유
    JsonNode r1 = receipt(ap1);
    assertThat(r1.path("receiverService").asString()).isEqualTo("pricing-service");
    assertThat(r1.path("receiptSeq").asInt()).isEqualTo(1);
    assertThat(r1.path("result").asString()).isEqualTo("PARTIAL");
    assertThat(r1.path("lines")).hasSize(3);
    assertThat(r1.path("lines").path(0).path("lineKey").asString()).isEqualTo(sku);
    assertThat(r1.path("lines").path(0).path("result").asString()).isEqualTo("APPLIED");
    assertThat(r1.path("lines").path(0).path("domainVersion").asInt()).isEqualTo(1);
    assertThat(r1.path("lines").path(1).path("reasonCode").asString()).isEqualTo("SKU_NOT_FOUND");

    // 2) 예약 발효: 3초 뒤 효력 → 그 전엔 예약(nextChange), 지나면 주기 작업이 발효
    Instant later = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.MILLIS);
    String ap2 = priceApproved("APPROVED", List.of(row(sku, 4900, later)));
    awaitApproval(ap2);
    Res before = get(MD, "/prices/" + sku);
    assertThat(before.body().path("basePrice").asInt()).isEqualTo(4500);
    assertThat(before.body().path("nextChange").path("basePrice").asInt()).isEqualTo(4900);

    // 같은 시각 이전으로 또 예약하면 기간 겹침
    String ap3 = priceApproved("APPROVED", List.of(row(sku, 5000, later.minusMillis(500))));
    awaitApproval(ap3);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM price_approval_line WHERE approval_id = ?",
                String.class,
                ap3))
        .isEqualTo("PRICE_SCHEDULE_CONFLICT");
    JsonNode r3 = receipt(ap3);
    assertThat(r3.path("result").asString()).isEqualTo("REJECTED");
    assertThat(r3.path("lines").path(0).path("reasonCode").asString())
        .isEqualTo("PRICE_SCHEDULE_CONFLICT");

    Res after =
        await(
            "/price-versions/" + sku,
            r -> "EFFECTIVE".equals(r.body().path("items").path(0).path("state").asString()));
    assertThat(after.body().path("items").path(0).path("priceVersion").asInt()).isEqualTo(2);
    assertThat(after.body().path("items").path(1).path("state").asString()).isEqualTo("SUPERSEDED");
    assertThat(effectiveEvents(sku)).isEqualTo(2);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'PriceVersionEffective'"
                + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            sku);
    JsonNode event = mapper.readTree(payload);
    assertThat(event.path("payload").path("price").asInt()).isEqualTo(4900);
    assertThat(event.path("payload").path("previousPrice").asInt()).isEqualTo(4500);

    // 3) 견적: 단가 × 수량, 같은 키 재요청은 같은 견적, quoteId 재조회
    Map<String, Object> quoteReq =
        Map.of("items", List.of(Map.of("lineRef", "c-1", "sku", sku, "quantity", 3)));
    String key = UUID.randomUUID().toString();
    Res q1 = post(ORDER, key, "/internal/quotes", quoteReq);
    assertThat(q1.status()).isEqualTo(200);
    assertThat(q1.body().path("itemAmount").asInt()).isEqualTo(14700);
    assertThat(q1.body().path("lines").path(0).path("priceVersion").asInt()).isEqualTo(2);
    assertThat(q1.body().path("appliedStages").path(0).asString()).isEqualTo("BASE");
    Res q2 = post(ORDER, key, "/internal/quotes", quoteReq);
    assertThat(q2.body().path("quoteId").asString())
        .isEqualTo(q1.body().path("quoteId").asString());
    Res q3 = get(ORDER, "/internal/quotes/" + q1.body().path("quoteId").asString());
    assertThat(q3.body().path("itemAmount").asInt()).isEqualTo(14700);

    Res notSet =
        post(
            ORDER,
            UUID.randomUUID().toString(),
            "/internal/quotes",
            Map.of(
                "items",
                List.of(
                    Map.of("lineRef", "c-1", "sku", sku, "quantity", 1),
                    Map.of("lineRef", "c-2", "sku", unknown, "quantity", 1))));
    assertThat(notSet.status()).isEqualTo(422);
    assertThat(notSet.body().path("code").asString()).isEqualTo("PRICE_NOT_SET");

    // 운영자 토큰은 내부 경로를 못 쓴다
    assertThat(post(MD, UUID.randomUUID().toString(), "/internal/quotes", quoteReq).status())
        .isEqualTo(403);

    // 4) 반영 명령: 승인 범위와 같으면 결과, 다르면 APPROVAL_SCOPE_MISMATCH, 모르는 승인은 APPROVAL_REQUIRED
    String appKey = UUID.randomUUID().toString();
    Res applied =
        post(
            MD,
            appKey,
            "/price-version-applications",
            Map.of(
                "approvalId",
                ap2,
                "items",
                List.of(Map.of("sku", sku, "basePrice", 4900, "effectiveFrom", later.toString()))));
    assertThat(applied.status()).isEqualTo(202);
    assertThat(applied.body().path("status").asString()).isEqualTo("APPLIED");
    Res result = get(MD, "/price-version-applications/" + appKey);
    assertThat(result.body().path("items").path(0).path("priceVersion").asInt()).isEqualTo(2);
    assertThat(result.body().path("items").path(0).path("state").asString()).isEqualTo("EFFECTIVE");

    Res mismatch =
        post(
            MD,
            UUID.randomUUID().toString(),
            "/price-version-applications",
            Map.of(
                "approvalId",
                ap2,
                "items",
                List.of(Map.of("sku", sku, "basePrice", 5900, "effectiveFrom", later.toString()))));
    assertThat(mismatch.status()).isEqualTo(422);
    assertThat(mismatch.body().path("code").asString()).isEqualTo("APPROVAL_SCOPE_MISMATCH");

    Res missing =
        post(
            MD,
            UUID.randomUUID().toString(),
            "/price-version-applications",
            Map.of(
                "approvalId",
                "AP-none",
                "items",
                List.of(Map.of("sku", sku, "basePrice", 4900, "effectiveFrom", later.toString()))));
    assertThat(missing.status()).isEqualTo(422);
    assertThat(missing.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");
  }

  @Test
  void rejectedOrOtherApprovalsCreateNoVersion() throws Exception {
    String sku = "B-2" + String.format("%07d", (int) (Math.random() * 9_999_999));
    productPublished(sku);
    Thread.sleep(1000);
    String rejected = priceApproved("REJECTED", List.of(row(sku, 3000, null)));
    awaitApproval(rejected);
    assertThat(get(MD, "/prices/" + sku).status()).isEqualTo(404);
    assertThat(effectiveEvents(sku)).isZero();
    // 반려된 결정은 반영 요청이 아니라 회신하지 않는다
    assertThat(receipt(rejected)).isNull();
  }
}
