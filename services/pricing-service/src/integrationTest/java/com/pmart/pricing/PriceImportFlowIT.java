package com.pmart.pricing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가격 일괄 등록: 기존 가격 2건(승인) → seed-v1 묶음 업로드(원천 키 → SKU 는 product-service WireMock) →
 * NEW·CHANGE·UNCHANGED· SKU_NOT_FOUND 분류 → 행 가격 고치기(버전 확인) → 제출(PRICE 승인 요청 본문, 멱등, 잠금) →
 * ApprovalDecided(배치 대상) → 배치 APPLIED·행별 결과·새 가격. 실제 PostgreSQL·Kafka.
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
@EnableWireMock(
    @ConfigureWireMock(
        name = "product",
        baseUrlProperties = {"pmart.pricing.product-base-url"}))
class PriceImportFlowIT extends PostgresKafkaContainerSupport {

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String CUSTOMER = TestJwt.customer("cust-1");

  // 다른 시험과 겹치지 않는 SKU
  static final String SKU_A = "E-70000001"; // 가격 없음 → NEW
  static final String SKU_B = "E-70000002"; // 2000 → 묶음 2500 → CHANGE
  static final String SKU_C = "E-70000003"; // 3000 → 묶음 3000 → UNCHANGED

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("product")
  WireMockServer product;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    product.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/source-link-lookups"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[
                          {"sourceKey":"bm-a","productId":"P-A","skus":["%s"]},
                          {"sourceKey":"bm-b","productId":"P-B","skus":["%s"]},
                          {"sourceKey":"bm-c","productId":"P-C","skus":["%s"]}],
                         "missing":["bm-d"]}
                        """
                            .formatted(SKU_A, SKU_B, SKU_C))));
  }

  @Test
  void bundlePricesToOneApproval() throws Exception {
    for (String sku : List.of(SKU_A, SKU_B, SKU_C)) {
      productPublished(sku);
    }
    awaitTrue(
        () ->
            count("SELECT COUNT(*) FROM sku_projection WHERE sku IN (?,?,?)", SKU_A, SKU_B, SKU_C)
                == 3);
    String existing =
        approved(
            Map.of("targetType", "Price", "targetId", "PRICE-CHANGE"),
            List.of(
                Map.of("sku", SKU_B, "basePrice", 2000), Map.of("sku", SKU_C, "basePrice", 3000)));
    awaitTrue(
        () -> count("SELECT COUNT(*) FROM price_approval WHERE approval_id = ?", existing) == 1);

    // 운영자만
    assertThat(upload(CUSTOMER, "k-cust").status()).isEqualTo(403);

    // 업로드: 묶음 basePrice 로 미리 채우고 SKU·현재 가격과 대조
    Res up = upload(MD, "k-up-" + UUID.randomUUID());
    assertThat(up.status()).isEqualTo(201);
    JsonNode b = up.body();
    String batchId = b.get("batchId").asString();
    assertThat(b.get("state").asString()).isEqualTo("DRAFT");
    assertThat(b.get("sourceSystem").asString()).isEqualTo("bmart-capture");
    assertThat(b.get("lineCount").asInt()).isEqualTo(4);
    assertThat(b.get("submittableCount").asInt()).isEqualTo(2);
    JsonNode a = line(b, "bm-a");
    assertThat(a.get("sku").asString()).isEqualTo(SKU_A);
    assertThat(a.get("productName").asString()).isEqualTo("상품 bm-a");
    assertThat(a.get("changeType").asString()).isEqualTo("NEW");
    assertThat(a.get("status").asString()).isEqualTo("READY");
    assertThat(a.get("currentPrice").isNull()).isTrue();
    assertThat(line(b, "bm-b").get("changeType").asString()).isEqualTo("CHANGE");
    assertThat(line(b, "bm-b").get("currentPrice").asLong()).isEqualTo(2000);
    assertThat(line(b, "bm-c").get("status").asString()).isEqualTo("UNCHANGED");
    assertThat(line(b, "bm-d").get("status").asString()).isEqualTo("SKU_NOT_FOUND");
    assertThat(line(b, "bm-d").get("sku").isNull()).isTrue();

    // 행 가격 고치기: 버전이 다르면 409, 맞으면 반영(edited)
    int v = b.get("version").asInt();
    Res stale = put(batchId, "bm-a", Map.of("basePrice", 1100), v + 5);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().get("code").asString()).isEqualTo("VERSION_CONFLICT");
    Res edited = put(batchId, "bm-a", Map.of("basePrice", 1100), v);
    assertThat(edited.status()).isEqualTo(200);
    assertThat(line(edited.body(), "bm-a").get("basePrice").asLong()).isEqualTo(1100);
    assertThat(line(edited.body(), "bm-a").get("bundlePrice").asLong()).isEqualTo(1000);
    assertThat(line(edited.body(), "bm-a").get("edited").asBoolean()).isTrue();
    // 0원은 받지 않는다
    assertThat(put(batchId, "bm-a", Map.of("basePrice", 0), null).status()).isEqualTo(400);
    // 빼기 → 다시 넣기
    Res ex = put(batchId, "bm-b", Map.of("excluded", true), null);
    assertThat(line(ex.body(), "bm-b").get("status").asString()).isEqualTo("EXCLUDED");
    assertThat(ex.body().get("submittableCount").asInt()).isEqualTo(1);
    put(batchId, "bm-b", Map.of("excluded", false), null);

    // 다시 대조: 설정 유지
    Res refreshed =
        post("/price-import-batches/" + batchId + "/refreshes", "k-rf-" + batchId, null, null);
    assertThat(refreshed.status()).isEqualTo(200);
    assertThat(line(refreshed.body(), "bm-a").get("basePrice").asLong()).isEqualTo(1100);
    assertThat(refreshed.body().get("submittableCount").asInt()).isEqualTo(2);

    // 지난 효력 시각은 거절
    Res past =
        post(
            "/price-import-batches/" + batchId + "/submissions",
            "k-past-" + batchId,
            Map.of("effectiveFrom", Instant.now().minusSeconds(3600).toString()),
            null);
    assertThat(past.status()).isEqualTo(422);
    assertThat(past.body().get("code").asString()).isEqualTo("EFFECTIVE_FROM_IN_PAST");

    // 제출: NEW·CHANGE 2행 → PRICE 승인 요청 한 건
    String submitKey = "k-sub-" + batchId;
    Res sub = post("/price-import-batches/" + batchId + "/submissions", submitKey, Map.of(), null);
    assertThat(sub.status()).isEqualTo(200);
    JsonNode req = sub.body().get("approvalRequest");
    assertThat(req.get("type").asString()).isEqualTo("PRICE");
    assertThat(req.get("targetService").asString()).isEqualTo("pricing-service");
    assertThat(req.get("targetRef").asString()).isEqualTo("priceImportBatch:" + batchId);
    assertThat(req.get("lines")).hasSize(2);
    JsonNode la = req.get("lines").get(0);
    assertThat(la.get("lineKey").asString()).isEqualTo(SKU_A);
    assertThat(la.get("requestedAmount").asLong()).isEqualTo(1100);
    assertThat(la.get("attributes").get("changeType").asString()).isEqualTo("NEW");
    JsonNode lb = req.get("lines").get(1);
    assertThat(lb.get("attributes").get("previousPrice").asLong()).isEqualTo(2000);
    assertThat(lb.get("attributes").get("basePrice").asLong()).isEqualTo(2500);
    assertThat(sub.body().get("batch").get("state").asString()).isEqualTo("SUBMITTED");
    // 같은 키 재전송 = 같은 결과, 제출 뒤 수정은 잠김
    Res again =
        post("/price-import-batches/" + batchId + "/submissions", submitKey, Map.of(), null);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().get("approvalRequest")).isEqualTo(req);
    Res locked = put(batchId, "bm-a", Map.of("basePrice", 1200), null);
    assertThat(locked.status()).isEqualTo(409);
    assertThat(locked.body().get("code").asString()).isEqualTo("BATCH_LOCKED");

    // 승인(approval-service 가 낸 ApprovalDecided 를 흉내 — 요청 본문의 행 그대로)
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode l : req.get("lines")) {
      rows.add(mapper.convertValue(l.get("attributes"), Map.class));
    }
    String approvalId =
        approved(Map.of("targetType", "Price", "targetId", req.get("targetRef").asString()), rows);
    Res done =
        await(
            "/price-import-batches/" + batchId,
            r -> "APPLIED".equals(r.body().path("state").asString()));
    assertThat(done.body().get("state").asString()).isEqualTo("APPLIED");
    assertThat(done.body().get("approvalId").asString()).isEqualTo(approvalId);
    assertThat(line(done.body(), "bm-a").get("result").get("state").asString())
        .isEqualTo("APPLIED");
    assertThat(line(done.body(), "bm-b").get("result").get("priceVersion").asInt()).isEqualTo(2);
    // 제출하지 않은 행(UNCHANGED)에는 결과가 없다(null 또는 생략)
    assertThat(line(done.body(), "bm-c").path("result").isObject()).isFalse();

    Res priceA = get("/prices/" + SKU_A);
    assertThat(priceA.body().get("basePrice").asLong()).isEqualTo(1100);
    Res priceB = get("/prices/" + SKU_B);
    assertThat(priceB.body().get("basePrice").asLong()).isEqualTo(2500);
  }

  // ---------- 도우미 ----------

  static byte[] bundle() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      Map<String, Integer> prices = new LinkedHashMap<>();
      prices.put("bm-a", 1000);
      prices.put("bm-b", 2500);
      prices.put("bm-c", 3000);
      prices.put("bm-d", 500);
      for (var e : prices.entrySet()) {
        zip.putNextEntry(new ZipEntry("seed-v1/products/" + e.getKey() + "/product.json"));
        zip.write(
            """
            {"format":"seed-v1","product":{"seedProductId":"%s","name":"상품 %s"},
             "price":{"basePrice":%d},"source":{"system":"bmart-capture"}}
            """
                .formatted(e.getKey(), e.getKey(), e.getValue())
                .getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("seed-v1/products/" + e.getKey() + "/images/main.jpg"));
        zip.write(new byte[] {1, 2, 3});
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  Res upload(String token, String key) throws Exception {
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "file",
        new ByteArrayResource(bundle()) {
          @Override
          public String getFilename() {
            return "seed-v1.zip";
          }
        });
    var e =
        http.post()
            .uri("/price-import-batches")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .retrieve()
            .toEntity(String.class);
    return res(e.getStatusCode().value(), e.getBody());
  }

  Res put(String batchId, String seedProductId, Map<String, Object> body, Integer version) {
    var e =
        http.put()
            .uri("/price-import-batches/{b}/lines/{s}", batchId, seedProductId)
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", "k-put-" + UUID.randomUUID());
                  h.set("X-Source-System", "backoffice-web");
                  if (version != null) {
                    h.set("X-Expected-Version", String.valueOf(version));
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return res(e.getStatusCode().value(), e.getBody());
  }

  Res post(String path, String key, Object body, Integer version) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                  if (version != null) {
                    h.set("X-Expected-Version", String.valueOf(version));
                  }
                });
    if (body != null) {
      spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return res(e.getStatusCode().value(), e.getBody());
  }

  Res get(String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(MD)).retrieve().toEntity(String.class);
    return res(e.getStatusCode().value(), e.getBody());
  }

  Res res(int status, String body) {
    return new Res(status, mapper.readTree(body == null || body.isEmpty() ? "{}" : body));
  }

  Res await(String path, Predicate<Res> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    Res r = get(path);
    while (!done.test(r) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      r = get(path);
    }
    return r;
  }

  void awaitTrue(java.util.function.BooleanSupplier ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (!ok.getAsBoolean()) {
      if (Instant.now().isAfter(until)) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(200);
    }
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  static JsonNode line(JsonNode batch, String seedProductId) {
    for (JsonNode l : batch.get("lines")) {
      if (seedProductId.equals(l.get("seedProductId").asString())) {
        return l;
      }
    }
    throw new AssertionError("no line " + seedProductId);
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

  /** approval-service 가 내는 ApprovalDecided(PRICE, 승인) 를 흉내 낸다. */
  String approved(Map<String, Object> target, List<Map<String, Object>> rows) {
    String approvalId = "AP-" + UUID.randomUUID();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("lineId", row.get("sku"));
      l.put("amount", row.get("basePrice"));
      l.put("decision", "APPROVED");
      l.put("attributes", row);
      lines.add(l);
    }
    Map<String, Object> targetRef = new LinkedHashMap<>(target);
    targetRef.put("targetService", "pricing-service");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + UUID.randomUUID());
    payload.put("type", "PRICE");
    payload.put("decision", "APPROVED");
    payload.put("targetRef", targetRef);
    payload.put("lines", lines);
    payload.put("approverId", "price-lead-1");
    payload.put("dossierVersion", 1);
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
}
