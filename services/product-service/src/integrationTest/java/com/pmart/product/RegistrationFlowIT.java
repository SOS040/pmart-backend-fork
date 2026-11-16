package com.pmart.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.product.domain.BarcodeType;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 상품 등록 흐름(임시 백오피스): 카테고리 트리 초안 → 승인(ApprovalDecided 를 Kafka 로 흘림) → 발효 → seed-v1 배치 업로드(경로 이름 자동
 * 매핑) → 제출 준비 → 승인 → SKU 발급·바코드·버전 발행(Outbox). 실제 PostgreSQL·Kafka, 시험 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class RegistrationFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());

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

  Res post(String path, Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    if (body instanceof LinkedMultiValueMap<?, ?> form) {
      spec = spec.contentType(MediaType.MULTIPART_FORM_DATA).body(form);
    } else if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(MD)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res await(String path, Predicate<JsonNode> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    Res r = get(path);
    while (!done.test(r.body()) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      r = get(path);
    }
    return r;
  }

  /** approval-service 가 내는 ApprovalDecided 를 흉내 낸다(제출 준비가 돌려준 본문의 행 그대로). */
  String decide(JsonNode approvalRequest, String decision) {
    String approvalId = "AP-" + UUID.randomUUID();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (JsonNode l : approvalRequest.path("lines")) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineId", l.path("lineKey").asString());
      line.put("decision", decision);
      line.put("targetRef", l.path("targetRef").asString());
      line.put("attributes", mapper.convertValue(l.path("attributes"), Map.class));
      lines.add(line);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + UUID.randomUUID());
    payload.put("type", "PRODUCT");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Product",
            "targetId",
            approvalRequest.path("targetRef").asString(),
            "targetService",
            "product-service"));
    payload.put("lines", lines);
    payload.put("approverId", "lead-1");
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

  static Map<String, Object> tree() {
    var chip =
        Map.of(
            "id",
            "p03-c01-l01",
            "name",
            "국",
            "level",
            "chip1",
            "filters",
            List.of(),
            "children",
            List.of());
    var sub1 =
        Map.of(
            "id",
            "p03-c01",
            "name",
            "국·국밥",
            "level",
            "sub",
            "filters",
            List.of("filter_a"),
            "children",
            List.of(chip));
    var sub2 =
        Map.of(
            "id",
            "p03-c02",
            "name",
            "탕·찌개",
            "level",
            "sub",
            "filters",
            List.of("filter_a"),
            "children",
            List.of());
    var top =
        Map.of(
            "id",
            "p03",
            "name",
            "국·탕·찌개",
            "level",
            "top",
            "section",
            "맛있는것",
            "filters",
            List.of("filter_a"),
            "children",
            List.of(sub1, sub2));
    var filter =
        Map.of(
            "id",
            "filter_a",
            "group",
            "보관",
            "selectionMode",
            "multiple",
            "common",
            true,
            "options",
            List.of("냉동", "냉장"));
    return Map.of("sourceNote", "시험 트리", "categories", List.of(top), "filters", List.of(filter));
  }

  byte[] seedZip(Map<String, Map<String, Object>> products) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("seed-v1/manifest.json"));
      zip.write("{\"format\":\"seed-v1\"}".getBytes(StandardCharsets.UTF_8));
      for (var e : products.entrySet()) {
        zip.putNextEntry(new ZipEntry("seed-v1/products/" + e.getKey() + "/product.json"));
        zip.write(mapper.writeValueAsBytes(e.getValue()));
        zip.putNextEntry(new ZipEntry("seed-v1/products/" + e.getKey() + "/images/main.jpg"));
        zip.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
      }
    }
    return out.toByteArray();
  }

  static Map<String, Object> seedProduct(
      String id, String name, List<List<String>> categories, Integer weightG) {
    Map<String, Object> sku = new LinkedHashMap<>();
    sku.put("sku", null);
    sku.put("unitType", "E");
    sku.put("unitsPerSku", 1);
    sku.put("weightG", weightG);
    sku.put("storageType", "FROZEN");
    sku.put("shelfLifeGuarantee", Map.of("minRemainingDays", 91)); // 상품 고시 "주문일 기준 91일 이상"
    sku.put(
        "dimensions", Map.of("widthMm", 160, "depthMm", 40, "heightMm", 230)); // 추정 치수(2026-11-16)
    sku.put("dimensionsSource", "ESTIMATED");
    sku.put("alcohol", null);
    sku.put("barcodes", List.of());
    Map<String, Object> product = new LinkedHashMap<>();
    product.put("seedProductId", id);
    product.put("baseProductKey", name + "|180g");
    product.put("internalName", name);
    product.put("primaryCategory", categories.isEmpty() ? null : categories.get(0));
    product.put("categories", categories);
    product.put("saleState", "ON_SALE");
    return Map.of(
        "format",
        "seed-v1",
        "product",
        product,
        "skus",
        List.of(sku),
        "source",
        Map.of("system", "bmart-capture", "derived", List.of("weightG", "storageType")));
  }

  LinkedMultiValueMap<String, Object> form(byte[] zip) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(zip) {
          @Override
          public String getFilename() {
            return "seed-v1.zip";
          }
        });
    return form;
  }

  @Test
  void treeApprovalThenSeedBatchIssuesSkusAndPublishesVersions() throws Exception {
    // 1) 트리 초안 → 승인 → 발효. 발효 전에는 배치 업로드가 막힌다
    Res draftTree = post("/category-tree-versions", tree());
    assertThat(draftTree.status()).isEqualTo(201);
    assertThat(draftTree.body().path("state").asString()).isEqualTo("DRAFT");
    assertThat(draftTree.body().path("countsByLevel").path("SUB").asInt()).isEqualTo(2);
    int version = draftTree.body().path("version").asInt();

    byte[] zip =
        seedZip(
            Map.of(
                "bm-ok",
                    seedProduct(
                        "bm-ok", "[냉동]호밍스 묵은지부대찌개 180g", List.of(List.of("국·탕·찌개", "탕·찌개")), 180),
                "bm-unmapped",
                    seedProduct("bm-unmapped", "모르는 분류 상품", List.of(List.of("비웰")), 100)));
    if (get("/categories/tree").status() == 404) {
      assertThat(post("/product-import-batches", form(zip)).body().path("code").asString())
          .isEqualTo("CATEGORY_TREE_NOT_EFFECTIVE");
    }

    decide(draftTree.body().path("approvalRequest"), "APPROVED");
    assertThat(
            await(
                    "/category-tree-versions/" + version,
                    b -> "EFFECTIVE".equals(b.path("state").asString()))
                .body()
                .path("state")
                .asString())
        .isEqualTo("EFFECTIVE");
    JsonNode treeView = get("/categories/tree").body();
    assertThat(treeView.path("nodes")).hasSize(4);
    assertThat(
            get("/categories/p03-c02/filters")
                .body()
                .path("groups")
                .path(0)
                .path("options")
                .path(0)
                .asString())
        .isEqualTo("냉동");

    // 2) seed-v1 배치: 경로 이름 자동 매핑, 매핑 안 되는 상품은 INVALID
    Res batch = post("/product-import-batches", form(zip));
    assertThat(batch.status()).isEqualTo(201);
    assertThat(batch.body().path("validCount").asInt()).isEqualTo(1);
    assertThat(batch.body().path("invalidCount").asInt()).isEqualTo(1);
    String batchId = batch.body().path("batchId").asString();
    JsonNode okDraft = null;
    for (JsonNode d : batch.body().path("drafts")) {
      if ("bm-ok".equals(d.path("sourceKey").asString())) {
        okDraft = d;
      } else {
        assertThat(d.path("problems").path(0).path("code").asString())
            .isEqualTo("CATEGORY_NOT_MAPPED");
      }
    }
    assertThat(okDraft.path("resolvedCategoryIds").path(0).asString()).isEqualTo("p03-c02");
    assertThat(okDraft.path("registration").asString()).isEqualTo("NEW"); // BR-39: 처음 올린 상품
    assertThat(okDraft.path("existingProductId").isNull()).isTrue();
    assertThat(okDraft.path("input").path("derivedFields").toString()).contains("alcohol");

    // 3) 제출 준비 → 승인
    Res submission = post("/product-import-batches/" + batchId + "/submissions", null);
    assertThat(submission.status()).isEqualTo(200);
    assertThat(submission.body().path("submittedDraftIds")).hasSize(1);
    assertThat(submission.body().path("excludedDraftIds")).hasSize(1);
    // BR-41: 승인 대기 동안 배치 조회가 제출 결과(같은 승인 요청 본문)를 다시 준다
    JsonNode pending = get("/product-import-batches/" + batchId).body().path("pendingSubmission");
    assertThat(pending.path("approvalRequest"))
        .isEqualTo(submission.body().path("approvalRequest"));
    assertThat(pending.path("submittedDraftIds"))
        .isEqualTo(submission.body().path("submittedDraftIds"));
    decide(submission.body().path("approvalRequest"), "APPROVED");

    String draftId = okDraft.path("draftId").asString();
    JsonNode applied =
        await("/product-drafts/" + draftId, b -> "APPLIED".equals(b.path("state").asString()))
            .body();
    assertThat(applied.path("state").asString()).isEqualTo("APPLIED");
    String sku = applied.path("resultSkus").path(0).asString();
    assertThat(sku).matches("^E-[0-9]{8}$");
    String productId = applied.path("resultProductId").asString();

    JsonNode product = get("/products/" + productId).body();
    assertThat(product.path("primaryCategoryId").asString()).isEqualTo("p03-c02");
    assertThat(product.path("skus").path(0).path("logistics").path("storageType").asString())
        .isEqualTo("FROZEN");
    assertThat(get("/skus/" + sku).body().path("weightG").asInt()).isEqualTo(180);
    // 소비기한 보장(ADR-0041): 묶음 값 그대로, 없는 항목은 비운다(0 으로 채우지 않음)
    JsonNode guarantee = get("/skus/" + sku).body().path("shelfLifeGuarantee");
    assertThat(guarantee.path("minRemainingDays").asInt()).isEqualTo(91);
    assertThat(guarantee.hasNonNull("maxDaysSinceManufacture")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'ProductVersionPublished'"
                    + " AND aggregateid = ?",
                String.class,
                productId))
        .contains("\"shelfLifeGuarantee\"");
    // 실물 치수(2026-11-16): 묶음 값 그대로 조회·ProductVersionPublished 에 나간다
    JsonNode skuDetail = get("/skus/" + sku).body();
    assertThat(skuDetail.path("dimensions").path("widthMm").asInt()).isEqualTo(160);
    assertThat(skuDetail.path("dimensions").path("depthMm").asInt()).isEqualTo(40);
    assertThat(skuDetail.path("dimensions").path("heightMm").asInt()).isEqualTo(230);
    assertThat(skuDetail.path("dimensionsSource").asString()).isEqualTo("ESTIMATED");
    assertThat(product.path("skus").path(0).path("dimensions").path("heightMm").asInt())
        .isEqualTo(230);
    String publishedPayload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'ProductVersionPublished'"
                + " AND aggregateid = ?",
            String.class,
            productId);
    JsonNode publishedSku = mapper.readTree(publishedPayload).path("payload").path("skus").path(0);
    if (publishedSku.isMissingNode()) {
      publishedSku = mapper.readTree(publishedPayload).path("skus").path(0);
    }
    assertThat(publishedSku.path("dimensions").path("widthMm").asInt()).isEqualTo(160);
    assertThat(publishedSku.path("dimensionsSource").asString()).isEqualTo("ESTIMATED");
    assertThat(
            get("/product-import-batches/" + batchId + "/id-map")
                .body()
                .path("products")
                .toString())
        .contains(sku)
        .contains("bm-ok");
    // 원천 키 → SKU (pricing 가격 일괄 등록이 부른다): 발급된 키만 items, 나머지는 missing
    assertThat(
            post(
                    "/internal/source-link-lookups",
                    Map.of("sourceSystem", "bmart-capture", "sourceKeys", List.of("bm-ok")))
                .status())
        .isEqualTo(403); // 운영자 토큰은 내부 경로를 못 쓴다
    // pricing 이 부르는 그대로: 토큰·Idempotency-Key 없이(내부 경로, 조회성 POST — excluded-paths)
    var lookup =
        http.post()
            .uri("/internal/source-link-lookups")
            .header("X-Source-System", "pricing-service")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of("sourceSystem", "bmart-capture", "sourceKeys", List.of("bm-ok", "bm-none")))
            .retrieve()
            .toEntity(String.class);
    assertThat(lookup.getStatusCode().value()).isEqualTo(200);
    JsonNode links = mapper.readTree(lookup.getBody());
    assertThat(links.path("items")).hasSize(1);
    assertThat(links.path("items").path(0).path("sourceKey").asString()).isEqualTo("bm-ok");
    assertThat(links.path("items").path(0).path("productId").asString()).isEqualTo(productId);
    assertThat(links.path("items").path(0).path("skus").path(0).asString()).isEqualTo(sku);
    assertThat(links.path("missing").toString()).contains("bm-none");
    assertThat(get("/products?categoryId=p03-c02").body().path("items")).hasSize(1);

    Integer published =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'ProductVersionPublished' AND aggregateid = ?",
            Integer.class,
            productId);
    Integer issued =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'SkuIssued' AND aggregateid = ?",
            Integer.class,
            sku);
    assertThat(published).isEqualTo(1);
    assertThat(issued).isEqualTo(1);

    Integer skus = jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class);
    Integer products = jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class);

    // 4) 같은 묶음을 다시 올려도 같은 상품(원천 키 upsert) — 승인하면 새 버전, SKU 재발급 없음
    //    BR-39: 다시 올린 초안은 registration=UPDATE + 갱신될 상품·SKU 를 보여 주고, 승인 행에도 같은 표시
    Res again = post("/product-import-batches", form(zip));
    String againBatch = again.body().path("batchId").asString();
    JsonNode againOk = null;
    for (JsonNode d : again.body().path("drafts")) {
      if ("bm-ok".equals(d.path("sourceKey").asString())) {
        againOk = d;
      }
    }
    assertThat(againOk.path("registration").asString()).isEqualTo("UPDATE");
    assertThat(againOk.path("existingProductId").asString()).isEqualTo(productId);
    assertThat(againOk.path("existingSkus").path(0).asString()).isEqualTo(sku);
    JsonNode againRequest =
        post("/product-import-batches/" + againBatch + "/submissions", null)
            .body()
            .path("approvalRequest");
    JsonNode againLine = againRequest.path("lines").path(0).path("attributes");
    assertThat(againLine.path("registration").asString()).isEqualTo("UPDATE");
    assertThat(againLine.path("existingProductId").asString()).isEqualTo(productId);
    decide(againRequest, "APPROVED");
    await("/products/" + productId, b -> b.path("version").asInt() == 2);
    assertThat(get("/products/" + productId).body().path("version").asInt()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isEqualTo(skus);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class))
        .isEqualTo(products);
  }

  /** 발효된 트리가 없으면 만들어 승인한다(시험 실행 순서와 무관하게). */
  void ensureTree() throws InterruptedException {
    if (get("/categories/tree").status() == 200) {
      return;
    }
    Res t = post("/category-tree-versions", tree());
    decide(t.body().path("approvalRequest"), "APPROVED");
    await(
        "/category-tree-versions/" + t.body().path("version").asInt(),
        b -> "EFFECTIVE".equals(b.path("state").asString()));
  }

  @Test
  void categoryAdditionFromSeedCategoriesThenSeedLinksByCategoryId() throws Exception {
    ensureTree();
    int base = get("/categories/tree").body().path("version").asInt();

    // seed-v1 categories.json 모양 그대로(다른 필드는 무시). 매핑 못 한 경로(id null)는 건너뛴다
    Map<String, Object> cola = new LinkedHashMap<>();
    cola.put("bmartPath", List.of("음료·커피·생수", "탄산음료", "콜라·사이다"));
    cola.put("products", 10);
    cola.put("pmartCategoryId", "p09-c01-l01");
    cola.put("pmartCategoryIdLevels", List.of("p09", "p09-c01", "p09-c01-l01"));
    Map<String, Object> fruit = new LinkedHashMap<>();
    fruit.put("bmartPath", List.of("음료·커피·생수", "탄산음료", "과일·기타탄산음료"));
    fruit.put("pmartCategoryIdLevels", List.of("p09", "p09-c01", "p09-c01-l02"));
    Map<String, Object> existing = new LinkedHashMap<>();
    existing.put("bmartPath", List.of("국·탕·찌개", "국·국밥"));
    existing.put("pmartCategoryIdLevels", List.of("p03", "p03-c01"));
    Map<String, Object> unmapped = new LinkedHashMap<>();
    unmapped.put("bmartPath", List.of("비웰"));
    unmapped.put("pmartCategoryIdLevels", null);
    Map<String, Object> body =
        Map.of("format", "seed-v1", "categories", List.of(cola, fruit, existing, unmapped));

    // 같은 id 를 다른 이름으로 주면 충돌(고치지 않음)
    Map<String, Object> renamed = new LinkedHashMap<>();
    renamed.put("bmartPath", List.of("국·탕·찌개", "다른 이름"));
    renamed.put("pmartCategoryIdLevels", List.of("p03", "p03-c01"));
    Res conflict = post("/category-tree-additions", Map.of("categories", List.of(renamed)));
    assertThat(conflict.status()).isEqualTo(400);
    assertThat(conflict.body().path("code").asString()).isEqualTo("CATEGORY_CONFLICT");

    Res draft = post("/category-tree-additions", body);
    assertThat(draft.status()).isEqualTo(201);
    assertThat(draft.body().path("state").asString()).isEqualTo("DRAFT");
    assertThat(draft.body().path("baseVersion").asInt()).isEqualTo(base);
    assertThat(mapper.convertValue(draft.body().path("addedCategoryIds"), List.class))
        .containsExactly("p09", "p09-c01", "p09-c01-l01", "p09-c01-l02");
    assertThat(draft.body().path("skippedPaths").path(0).asString()).isEqualTo("비웰");
    int version = draft.body().path("version").asInt();

    decide(draft.body().path("approvalRequest"), "APPROVED");
    await(
        "/category-tree-versions/" + version, b -> "EFFECTIVE".equals(b.path("state").asString()));
    JsonNode tree = get("/categories/tree").body();
    assertThat(tree.path("version").asInt()).isEqualTo(version);
    assertThat(tree.toString())
        .contains("\"categoryId\":\"p09-c01-l01\"")
        .contains("음료·커피·생수 > 탄산음료 > 콜라·사이다")
        .contains("\"categoryId\":\"p03-c02\"");
    // 기존 노드의 필터 패널은 그대로 복사된다
    assertThat(get("/categories/p03-c02/filters").body().path("groups")).hasSize(1);

    // 모두 이미 있으면 NOTHING_TO_ADD
    assertThat(post("/category-tree-additions", body).body().path("code").asString())
        .isEqualTo("NOTHING_TO_ADD");

    // seed-v1 상품: 이름 경로가 트리와 달라도 primaryCategoryId·categoryIds 로 연결된다
    Map<String, Object> seed =
        seedProduct(
            "bm-cola-" + UUID.randomUUID(),
            "코카콜라 500ml",
            List.of(List.of("음료 커피 생수", "탄산", "콜라")),
            520);
    @SuppressWarnings("unchecked")
    Map<String, Object> product = (Map<String, Object>) seed.get("product");
    product.put("primaryCategoryId", "p09-c01-l01");
    product.put("categoryIds", List.of("p09-c01-l01"));
    String seedId = (String) product.get("seedProductId");
    Res batch = post("/product-import-batches", form(seedZip(Map.of(seedId, seed))));
    assertThat(batch.body().path("validCount").asInt()).isEqualTo(1);
    assertThat(batch.body().path("drafts").path(0).path("resolvedCategoryIds").path(0).asString())
        .isEqualTo("p09-c01-l01");

    // id-map: seed-v1 id-map.json 모양(entries·identityKey)
    JsonNode idMap =
        get("/product-import-batches/" + batch.body().path("batchId").asString() + "/id-map")
            .body();
    assertThat(idMap.path("format").asString()).isEqualTo("seed-v1");
    assertThat(idMap.path("entries").path(0).path("seedProductId").asString()).isEqualTo(seedId);
    assertThat(idMap.path("entries").path(0).path("identityKey").asString())
        .isEqualTo("코카콜라 500ml|180g");
    assertThat(idMap.path("entries").path(0).path("pmartProductId").isNull()).isTrue();
  }

  /** BR-38: 배치의 유효·오류 수는 행의 현재 검증 결과 — 빠진 중량을 채워 저장하면 바로 반영된다. */
  @Test
  void batchCountsFollowDraftEdits() throws Exception {
    ensureTree();
    String id = "bm-w-" + UUID.randomUUID();
    Map<String, Object> seed =
        seedProduct(id, "중량 없는 상품", List.of(List.of("국·탕·찌개", "탕·찌개")), null);
    Res batch = post("/product-import-batches", form(seedZip(Map.of(id, seed))));
    String batchId = batch.body().path("batchId").asString();
    assertThat(batch.body().path("validCount").asInt()).isZero();
    assertThat(batch.body().path("invalidCount").asInt()).isEqualTo(1);
    JsonNode draft = batch.body().path("drafts").path(0);
    assertThat(draft.path("problems").toString()).contains("WEIGHT_REQUIRED");

    @SuppressWarnings("unchecked")
    Map<String, Object> input = mapper.convertValue(draft.path("input"), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> sku = ((List<Map<String, Object>>) input.get("skus")).get(0);
    sku.put("weightG", 180);
    // 조회도 운영자만(2026-11-15 — 서비스 안 검사): 고객 토큰으로 초안·배치·ID 맵·트리 버전 403
    String customer = TestJwt.customer("c-1");
    for (String path :
        List.of(
            "/product-drafts/" + draft.path("draftId").asString(),
            "/product-import-batches/x",
            "/product-import-batches/x/id-map",
            "/category-tree-versions/1",
            // 상품·SKU·바코드·명령 결과 조회도(권한 빈틈 정리 2026-11-17)
            "/products",
            "/products/PRD-none",
            "/skus/E-00000001",
            "/barcodes/2990000000011",
            "/products/PRD-none/sale-state-changes/r-1",
            "/sku-issuances/r-1")) {
      Integer status =
          http.get()
              .uri(path)
              .headers(h -> h.setBearerAuth(customer))
              .exchange((rq, rs) -> rs.getStatusCode().value());
      assertThat(status).as(path).isEqualTo(403);
    }
    var e =
        http.put()
            .uri("/product-drafts/" + draft.path("draftId").asString())
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  h.set("X-Expected-Version", draft.path("version").asString());
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(input)
            .retrieve()
            .toEntity(String.class);
    assertThat(e.getStatusCode().value()).isEqualTo(200);

    JsonNode after = get("/product-import-batches/" + batchId).body();
    assertThat(after.path("validCount").asInt()).isEqualTo(1);
    assertThat(after.path("invalidCount").asInt()).isZero();
    assertThat(after.path("productCount").asInt()).isEqualTo(1);
  }

  /** 카테고리 추가를 seed-v1 묶음(zip)으로(2026-11-03): 묶음의 categories.json 을 꺼내 JSON 경로와 같게 처리한다. */
  @Test
  void categoryAdditionFromSeedZip() throws Exception {
    ensureTree();
    String top = "pz" + (System.nanoTime() % 1_000_000);
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("bmartPath", List.of("묶음시험 " + top, "하위"));
    node.put("pmartCategoryId", top + "-c01");
    node.put("pmartCategoryIdLevels", List.of(top, top + "-c01"));
    node.put("reason", "same category as B마트");

    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("seed-v1/manifest.json"));
      zip.write("{\"format\":\"seed-v1\"}".getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("seed-v1/categories.json"));
      zip.write(mapper.writeValueAsBytes(Map.of("format", "seed-v1", "categories", List.of(node))));
      zip.putNextEntry(new ZipEntry("seed-v1/products/bm-x/product.json"));
      zip.write("{\"format\":\"seed-v1\"}".getBytes(StandardCharsets.UTF_8));
    }
    Res draft = post("/category-tree-addition-uploads", form(out.toByteArray()));
    assertThat(draft.status()).isEqualTo(201);
    assertThat(draft.body().path("state").asString()).isEqualTo("DRAFT");
    assertThat(mapper.convertValue(draft.body().path("addedCategoryIds"), List.class))
        .containsExactly(top, top + "-c01");
    assertThat(draft.body().path("approvalRequest").path("type").asString()).isEqualTo("PRODUCT");

    // BR-48: 발효 트리에 이미 있는 카테고리만 담긴 묶음 → 500 이 아니라 422 NOTHING_TO_ADD
    Map<String, Object> existing = new LinkedHashMap<>();
    existing.put("bmartPath", List.of("국·탕·찌개", "국·국밥"));
    existing.put("pmartCategoryIdLevels", List.of("p03", "p03-c01"));
    var onlyExisting = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(onlyExisting)) {
      zip.putNextEntry(new ZipEntry("seed-v1/categories.json"));
      zip.write(
          mapper.writeValueAsBytes(Map.of("format", "seed-v1", "categories", List.of(existing))));
    }
    Res nothing = post("/category-tree-addition-uploads", form(onlyExisting.toByteArray()));
    assertThat(nothing.status()).isEqualTo(422);
    assertThat(nothing.body().path("code").asString()).isEqualTo("NOTHING_TO_ADD");

    // categories.json 이 없는 묶음은 422 BUNDLE_INVALID
    Res none = post("/category-tree-addition-uploads", form(seedZip(Map.of())));
    assertThat(none.status()).isEqualTo(422);
    assertThat(none.body().path("code").asString()).isEqualTo("BUNDLE_INVALID");
  }

  /** 검사 숫자를 붙인 GTIN(2026-11-17 — 초안 검증이 GS1 검사 숫자를 본다). */
  static String gtin(String body) {
    return body + BarcodeType.checkDigit(body);
  }

  /** 시험마다 다른 EAN-13(선행 0 — 실제 제조사 대역과 겹치지 않게). */
  static String randomGtin13() {
    return gtin("0" + String.format("%011d", Math.floorMod(System.nanoTime(), 100_000_000_000L)));
  }

  Res put(String path, Object body) {
    return putAs(MD, path, body);
  }

  Res putAs(String token, String path, Object body) {
    var e =
        http.put()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 상품의 ProductVersionPublished(Outbox 봉투의 payload) 중 그 버전. */
  JsonNode publishedVersion(String productId, int version) {
    for (String row :
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'ProductVersionPublished'"
                + " AND aggregateid = ?",
            String.class,
            productId)) {
      JsonNode env = mapper.readTree(row);
      JsonNode payload = env.path("payload").isObject() ? env.path("payload") : env;
      if (payload.path("version").asInt() == version) {
        return payload;
      }
    }
    throw new AssertionError("ProductVersionPublished v" + version + " 없음: " + productId);
  }

  JsonNode approveDraft(String draftId) throws InterruptedException {
    Res sub = post("/product-drafts/" + draftId + "/submissions", null);
    assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
    decide(sub.body().path("approvalRequest"), "APPROVED");
    JsonNode done =
        await("/product-drafts/" + draftId, b -> "APPLIED".equals(b.path("state").asString()))
            .body();
    assertThat(done.path("state").asString()).as(done.toString()).isEqualTo("APPLIED");
    return done;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> firstSku(Map<String, Object> input) {
    return ((List<Map<String, Object>>) input.get("skus")).get(0);
  }

  /**
   * 상품 정보 수정(2026-11-17): 수정 시작 → 현재 승인 버전으로 채운 초안 → 이름 변경·바코드 추가 → 승인 → 새 버전이 고친 값과 건드리지 않은
   * 바코드·치수·소비기한 보장·용량을 함께 갖고 ProductVersionPublished 에 바코드가 실린다. 바코드 교체(빼면 퇴역)·검사 숫자·중복 초안 막기·다른 상품
   * 바코드 충돌도 본다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void editDraftPrefillsCurrentVersionAndApprovalKeepsUntouchedValues() throws Exception {
    ensureTree();
    String a = randomGtin13();
    Map<String, Object> skuIn =
        new LinkedHashMap<>((Map<String, Object>) ((List<?>) water(a).get("skus")).get(0));
    skuIn.put("volumeMl", 500);
    skuIn.put("shelfLifeGuarantee", Map.of("minRemainingDays", 30));
    skuIn.put("dimensions", Map.of("widthMm", 65, "depthMm", 65, "heightMm", 210));
    skuIn.put("dimensionsSource", "MEASURED");
    skuIn.put("logistics", Map.of("unitsPerBox", 20));
    Map<String, Object> first = new LinkedHashMap<>(water(a));
    first.put("skus", List.of(skuIn));
    JsonNode created =
        approveDraft(post("/product-drafts", first).body().path("draftId").asString());
    String productId = created.path("resultProductId").asString();
    String sku = created.path("resultSkus").path(0).asString();

    // MD 가 아니면 403(사용자 결정 2026-11-17 — 수정 시작은 MD)
    Res notMd = send(CLERK, "/products/" + productId + "/edit-drafts", java.util.Map.of());
    assertThat(notMd.status()).isEqualTo(403);

    // 없는 상품 404
    Res none = post("/products/PRD-none/edit-drafts", null);
    assertThat(none.status()).isEqualTo(404);
    assertThat(none.body().path("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");

    // 1) 수정 시작: 현재 값이 다 들어 있고(판매 상태·원천 키는 없음) UPDATE
    Res start = post("/products/" + productId + "/edit-drafts", null);
    assertThat(start.status()).as(start.body().toString()).isEqualTo(201);
    JsonNode draft = start.body();
    String draftId = draft.path("draftId").asString();
    assertThat(draft.path("validationState").asString()).isEqualTo("VALID");
    assertThat(draft.path("registration").asString()).isEqualTo("UPDATE");
    assertThat(draft.path("existingProductId").asString()).isEqualTo(productId);
    JsonNode in = draft.path("input");
    assertThat(in.path("targetProductId").asString()).isEqualTo(productId);
    assertThat(in.path("product").path("internalName").asString()).isEqualTo("삼다수 500mL");
    assertThat(in.path("product").hasNonNull("saleState")).isFalse();
    assertThat(in.hasNonNull("sourceKey")).isFalse();
    assertThat(in.path("product").path("categoryIds").path(0).asString()).isEqualTo("p03");
    JsonNode s0 = in.path("skus").path(0);
    assertThat(s0.path("unitType").asString()).isEqualTo("E");
    assertThat(s0.path("unitsPerSku").asInt()).isEqualTo(1);
    assertThat(s0.path("weightG").asInt()).isEqualTo(520);
    assertThat(s0.path("volumeMl").asInt()).isEqualTo(500);
    assertThat(s0.path("storageType").asString()).isEqualTo("AMBIENT");
    assertThat(s0.path("alcohol").asBoolean()).isFalse();
    assertThat(s0.path("shelfLifeGuarantee").path("minRemainingDays").asInt()).isEqualTo(30);
    assertThat(s0.path("dimensions").path("heightMm").asInt()).isEqualTo(210);
    assertThat(s0.path("dimensionsSource").asString()).isEqualTo("MEASURED");
    assertThat(s0.path("logistics").path("unitsPerBox").asInt()).isEqualTo(20);
    assertThat(s0.path("barcodes")).hasSize(1);
    assertThat(s0.path("barcodes").path(0).asString()).isEqualTo(a);

    // 2) 열린 수정 초안이 있으면 409 + 그 초안 id
    Res again = post("/products/" + productId + "/edit-drafts", null);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("EDIT_DRAFT_EXISTS");
    assertThat(again.body().path("details").path(0).path("field").asString())
        .isEqualTo("existingDraftId");
    assertThat(again.body().path("details").path(0).path("reason").asString()).isEqualTo(draftId);
    // 예전 길(초안 생성에 targetProductId 직접)도 같은 409(사용자 결정 2026-11-17 "(가)")
    Res oldPath = post("/product-drafts", in);
    assertThat(oldPath.status()).as(oldPath.body().toString()).isEqualTo(409);
    assertThat(oldPath.body().path("code").asString()).isEqualTo("EDIT_DRAFT_EXISTS");
    assertThat(oldPath.body().path("details").path(0).path("reason").asString()).isEqualTo(draftId);
    // 다른 초안을 고쳐 이 상품을 가리키게 해도 409
    String newDraftId =
        post("/product-drafts", water(randomGtin13())).body().path("draftId").asString();
    Map<String, Object> retarget = new LinkedHashMap<>(water(randomGtin13()));
    retarget.put("targetProductId", productId);
    Res viaPut = put("/product-drafts/" + newDraftId, retarget);
    assertThat(viaPut.status()).as(viaPut.body().toString()).isEqualTo(409);
    assertThat(viaPut.body().path("code").asString()).isEqualTo("EDIT_DRAFT_EXISTS");
    // MD 가 아니면 예전 길로도 수정 대상을 정할 수 없다(사용자 2026-11-17 "MD제한 걸어줘") — 403
    assertThat(send(CLERK, "/product-drafts", in).status()).isEqualTo(403);
    assertThat(putAs(CLERK, "/product-drafts/" + newDraftId, retarget).status()).isEqualTo(403);
    // 이미 정해진 수정 초안을 고치는 것은 예전처럼 운영자 누구나
    Res clerkEdit = putAs(CLERK, "/product-drafts/" + draftId, in);
    assertThat(clerkEdit.status()).as(clerkEdit.body().toString()).isEqualTo(200);

    // 3) 검사 숫자가 틀린 바코드는 INVALID, 고치면 VALID
    Map<String, Object> edit = mapper.convertValue(in, Map.class);
    ((Map<String, Object>) edit.get("product")).put("internalName", "삼다수 500mL (리뉴얼)");
    String b = randomGtin13();
    String badB = b.substring(0, 12) + ((b.charAt(12) - '0' + 1) % 10);
    firstSku(edit).put("barcodes", List.of(a, badB));
    Res invalid = put("/product-drafts/" + draftId, edit);
    assertThat(invalid.status()).isEqualTo(200);
    assertThat(invalid.body().path("validationState").asString()).isEqualTo("INVALID");
    assertThat(invalid.body().path("problems").path(0).path("code").asString())
        .isEqualTo("BARCODE_CHECK_DIGIT_INVALID");
    firstSku(edit).put("barcodes", List.of(a, b));
    Res fixed = put("/product-drafts/" + draftId, edit);
    assertThat(fixed.body().path("validationState").asString()).isEqualTo("VALID");

    // 4) 승인 → 같은 SKU 의 새 버전: 고친 이름 + 바코드 추가, 건드리지 않은 값은 그대로
    JsonNode applied = approveDraft(draftId);
    assertThat(applied.path("resultProductId").asString()).isEqualTo(productId);
    assertThat(applied.path("resultSkus").path(0).asString()).isEqualTo(sku);
    JsonNode product = get("/products/" + productId).body();
    assertThat(product.path("version").asInt()).isEqualTo(2);
    assertThat(product.path("internalName").asString()).isEqualTo("삼다수 500mL (리뉴얼)");
    JsonNode detail = get("/skus/" + sku).body();
    assertThat(detail.path("volumeMl").asInt()).isEqualTo(500);
    assertThat(detail.path("shelfLifeGuarantee").path("minRemainingDays").asInt()).isEqualTo(30);
    assertThat(detail.path("dimensions").path("widthMm").asInt()).isEqualTo(65);
    assertThat(detail.path("dimensionsSource").asString()).isEqualTo("MEASURED");
    assertThat(get("/barcodes/" + a).body().path("sku").asString()).isEqualTo(sku);
    assertThat(get("/barcodes/" + b).body().path("sku").asString()).isEqualTo(sku);
    JsonNode v2 = publishedVersion(productId, 2);
    assertThat(v2.path("internalName").asString()).isEqualTo("삼다수 500mL (리뉴얼)");
    JsonNode v2Sku = v2.path("skus").path(0);
    assertThat(v2Sku.path("barcodes").toString()).contains(a).contains(b);
    assertThat(v2Sku.path("dimensions").path("heightMm").asInt()).isEqualTo(210);
    assertThat(v2Sku.path("shelfLifeGuarantee").path("minRemainingDays").asInt()).isEqualTo(30);
    assertThat(v2Sku.path("volumeMl").asInt()).isEqualTo(500);

    // 5) 반영 뒤에는 다시 수정을 시작할 수 있다 — 바코드 교체(a 를 빼면 승인 때 퇴역)
    Res next = post("/products/" + productId + "/edit-drafts", null);
    assertThat(next.status()).isEqualTo(201);
    assertThat(next.body().path("input").path("skus").path(0).path("barcodes").toString())
        .contains(a)
        .contains(b);
    Map<String, Object> replace = mapper.convertValue(next.body().path("input"), Map.class);
    String c = randomGtin13();
    firstSku(replace).put("barcodes", List.of(b, c));
    String nextId = next.body().path("draftId").asString();
    assertThat(put("/product-drafts/" + nextId, replace).body().path("validationState").asString())
        .isEqualTo("VALID");
    approveDraft(nextId);
    assertThat(get("/barcodes/" + a).status()).isEqualTo(404);
    assertThat(get("/barcodes/" + c).body().path("sku").asString()).isEqualTo(sku);
    JsonNode v3Sku = publishedVersion(productId, 3).path("skus").path(0);
    assertThat(v3Sku.path("barcodes").toString()).doesNotContain(a).contains(b).contains(c);
    assertThat(v3Sku.path("dimensions").path("widthMm").asInt()).isEqualTo(65);

    // 6) 다른 상품의 활성 바코드를 넣으면 BARCODE_CONFLICT
    JsonNode other =
        approveDraft(
            post("/product-drafts", water(randomGtin13())).body().path("draftId").asString());
    Res otherEdit =
        post("/products/" + other.path("resultProductId").asString() + "/edit-drafts", null);
    assertThat(otherEdit.status()).isEqualTo(201);
    Map<String, Object> steal = mapper.convertValue(otherEdit.body().path("input"), Map.class);
    firstSku(steal).put("barcodes", List.of(c));
    Res conflict = put("/product-drafts/" + otherEdit.body().path("draftId").asString(), steal);
    assertThat(conflict.body().path("problems").path(0).path("code").asString())
        .isEqualTo("BARCODE_CONFLICT");
  }

  static Map<String, Object> water(String barcode) {
    return Map.of(
        "product", Map.of("internalName", "삼다수 500mL", "categoryPaths", List.of("국·탕·찌개")),
        "skus",
            List.of(
                Map.of(
                    "unitType",
                    "E",
                    "unitsPerSku",
                    1,
                    "weightG",
                    520,
                    "storageType",
                    "AMBIENT",
                    "alcohol",
                    false,
                    "barcodes",
                    List.of(barcode))));
  }

  @Test
  void barcodeOfIssuedSkuBlocksOtherDraftsAndRejectionKeepsNothing() throws Exception {
    ensureTree();
    String barcode = randomGtin13();

    // 바코드를 가진 상품 승인
    Res a = post("/product-drafts", water(barcode));
    assertThat(a.body().path("validationState").asString()).isEqualTo("VALID");
    String aId = a.body().path("draftId").asString();
    decide(
        post("/product-drafts/" + aId + "/submissions", null).body().path("approvalRequest"),
        "APPROVED");
    String sku =
        await("/product-drafts/" + aId, b -> "APPLIED".equals(b.path("state").asString()))
            .body()
            .path("resultSkus")
            .path(0)
            .asString();
    assertThat(get("/barcodes/" + barcode).body().path("sku").asString()).isEqualTo(sku);

    // 같은 바코드의 다른 초안은 충돌(자동 선택 없음), 제출도 막힌다
    Res b = post("/product-drafts", water(barcode));
    assertThat(b.body().path("validationState").asString()).isEqualTo("INVALID");
    assertThat(b.body().path("problems").path(0).path("code").asString())
        .isEqualTo("BARCODE_CONFLICT");
    assertThat(
            post("/product-drafts/" + b.body().path("draftId").asString() + "/submissions", null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("DRAFT_INVALID");

    // 반려된 초안은 상품·SKU 를 만들지 않는다
    Integer skusBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class);
    Res c = post("/product-drafts", water(gtin("9" + barcode.substring(0, 12))));
    String cId = c.body().path("draftId").asString();
    decide(
        post("/product-drafts/" + cId + "/submissions", null).body().path("approvalRequest"),
        "REJECTED");
    JsonNode rejected =
        await("/product-drafts/" + cId, x -> "REJECTED".equals(x.path("state").asString())).body();
    assertThat(rejected.path("problems").toString()).contains("APPROVAL_REJECTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class))
        .isEqualTo(skusBefore);
    assertThat(get("/barcodes/0000").status()).isEqualTo(404);
  }

  @Test
  void productWithoutSourceKeyIsUpdatedByTargetProductId() throws Exception {
    ensureTree();
    String barcode = randomGtin13();
    // 원천 키 없이 개별 등록한 상품
    Res a = post("/product-drafts", water(barcode));
    String aId = a.body().path("draftId").asString();
    decide(
        post("/product-drafts/" + aId + "/submissions", null).body().path("approvalRequest"),
        "APPROVED");
    JsonNode applied =
        await("/product-drafts/" + aId, b -> "APPLIED".equals(b.path("state").asString())).body();
    String productId = applied.path("resultProductId").asString();
    String sku = applied.path("resultSkus").path(0).asString();

    // 없는 상품 지정은 INVALID
    Map<String, Object> unknown = new LinkedHashMap<>(water(barcode));
    unknown.put("targetProductId", "PRD-none");
    Res bad = post("/product-drafts", unknown);
    assertThat(bad.body().path("problems").toString()).contains("TARGET_PRODUCT_NOT_FOUND");

    // 갱신 대상 지정 + 소비기한 보장 추가 → UPDATE, 같은 SKU 의 새 버전
    Map<String, Object> skuIn =
        new LinkedHashMap<>((Map<String, Object>) ((List<?>) water(barcode).get("skus")).get(0));
    skuIn.put("shelfLifeGuarantee", Map.of("maxDaysSinceManufacture", 3));
    skuIn.put("volumeMl", 500);
    skuIn.put("dimensions", Map.of("widthMm", 80, "depthMm", 80, "heightMm", 250));
    skuIn.put("dimensionsSource", "MEASURED");
    Map<String, Object> change = new LinkedHashMap<>(water(barcode));
    change.put("skus", List.of(skuIn));
    change.put("targetProductId", productId);
    Res draft = post("/product-drafts", change);
    assertThat(draft.body().path("validationState").asString()).isEqualTo("VALID");
    assertThat(draft.body().path("registration").asString()).isEqualTo("UPDATE");
    assertThat(draft.body().path("existingProductId").asString()).isEqualTo(productId);
    String dId = draft.body().path("draftId").asString();
    JsonNode request =
        post("/product-drafts/" + dId + "/submissions", null).body().path("approvalRequest");
    assertThat(request.toString()).contains("\"registration\":\"UPDATE\"");
    decide(request, "APPROVED");
    JsonNode done =
        await("/product-drafts/" + dId, b -> "APPLIED".equals(b.path("state").asString())).body();
    assertThat(done.path("resultProductId").asString()).isEqualTo(productId);
    assertThat(done.path("resultSkus").path(0).asString()).isEqualTo(sku);
    JsonNode detail = get("/skus/" + sku).body();
    assertThat(detail.path("version").asInt()).isEqualTo(2);
    assertThat(detail.path("shelfLifeGuarantee").path("maxDaysSinceManufacture").asInt())
        .isEqualTo(3);
    assertThat(detail.path("volumeMl").asInt()).isEqualTo(500);
    assertThat(get("/products/" + productId).body().path("skus").path(0).path("volumeMl").asInt())
        .isEqualTo(500);
    assertThat(detail.path("dimensions").path("heightMm").asInt()).isEqualTo(250);
    assertThat(detail.path("dimensionsSource").asString()).isEqualTo("MEASURED");

    // 치수 없이 다시 승인하면 소비기한 보장과 같이 초안 값 그대로 — 치수 미등록으로 바뀐다(승인 버전이 정본)
    Map<String, Object> noDims = new LinkedHashMap<>(water(barcode));
    noDims.put("targetProductId", productId);
    Res d3 = post("/product-drafts", noDims);
    assertThat(d3.body().path("validationState").asString()).isEqualTo("VALID");
    String d3Id = d3.body().path("draftId").asString();
    decide(
        post("/product-drafts/" + d3Id + "/submissions", null).body().path("approvalRequest"),
        "APPROVED");
    await("/product-drafts/" + d3Id, b -> "APPLIED".equals(b.path("state").asString()));
    JsonNode v3 = get("/skus/" + sku).body();
    assertThat(v3.path("version").asInt()).isEqualTo(3);
    assertThat(v3.hasNonNull("dimensions")).isFalse();
    assertThat(v3.hasNonNull("dimensionsSource")).isFalse();
    assertThat(v3.hasNonNull("shelfLifeGuarantee")).isFalse();
  }

  static final String ORDER = TestJwt.service("order-service");
  static final String CLERK = TestJwt.operator("clerk-1", List.of("CS"), List.of());

  Res send(String token, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 판매중지 승인(targetRef SALE-STATE:{productId}) 을 흉내 낸다. */
  String saleStopApproval(String productId) {
    String approvalId = "AP-" + UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", "PRODUCT");
    payload.put("decision", "APPROVED");
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Product",
            "targetId",
            "SALE-STATE:" + productId,
            "targetService",
            "product-service"));
    payload.put(
        "lines",
        List.of(
            Map.of(
                "lineId",
                productId,
                "decision",
                "APPROVED",
                "attributes",
                Map.of("saleState", "SALE_STOPPED"))));
    payload.put("approverId", "lead-1");
    payload.put("dossierVersion", 2);
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval.v1", approvalId, json).join();
    return approvalId;
  }

  @Test
  void skuIssuanceConfirmationPurchaseLimitAndSaleState() throws Exception {
    ensureTree();
    Res a = post("/product-drafts", water(gtin("1" + randomGtin13().substring(1, 12))));
    String aId = a.body().path("draftId").asString();
    String approvalId =
        decide(
            post("/product-drafts/" + aId + "/submissions", null).body().path("approvalRequest"),
            "APPROVED");
    JsonNode applied =
        await("/product-drafts/" + aId, b -> "APPLIED".equals(b.path("state").asString())).body();
    String sku = applied.path("resultSkus").path(0).asString();
    String productId = applied.path("resultProductId").asString();
    String baseProductId = get("/skus/" + sku).body().path("baseProductId").asString();

    // SkuIssued 는 계약 토픽 commerce.product.v1 로 나간다
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE type = 'SkuIssued' AND aggregateid = ?",
                String.class,
                sku))
        .isEqualTo("commerce.product.v1");

    // 1) SKU 발급 확인: 같은 판매 단위면 그 승인으로 발급된 SKU, 다르면 범위 불일치, 모르는 승인은 승인 필요
    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("approvalId", approvalId);
    issue.put("baseProductId", baseProductId);
    issue.put("unitType", "E");
    issue.put("unitsPerSku", 1);
    Res ok = post("/sku-issuances", issue);
    assertThat(ok.status()).isEqualTo(202);
    assertThat(ok.body().path("resultId").asString()).isEqualTo(sku);
    Map<String, Object> wrong = new LinkedHashMap<>(issue);
    wrong.put("unitsPerSku", 6);
    assertThat(post("/sku-issuances", wrong).body().path("code").asString())
        .isEqualTo("APPROVAL_SCOPE_MISMATCH");
    Map<String, Object> unknown = new LinkedHashMap<>(issue);
    unknown.put("approvalId", "AP-none");
    assertThat(post("/sku-issuances", unknown).body().path("code").asString())
        .isEqualTo("APPROVAL_REQUIRED");

    // 2) 구매 상한: 같은 기초상품 낱개 합 10 은 통과, 11 은 위반
    Res pass =
        send(
            ORDER,
            "/internal/purchase-limit-checks",
            Map.of("items", List.of(Map.of("sku", sku, "quantity", 10))));
    assertThat(pass.status()).isEqualTo(200);
    assertThat(pass.body().path("passed").asBoolean()).isTrue();
    assertThat(pass.body().path("limitPerBaseProduct").path("value").asInt()).isEqualTo(10);
    // 계약대로 Idempotency-Key 없이(조회성 검증) — cart 가 이렇게 부른다(2026-10-30 LAN 에서 400 으로 발견)
    var noKey =
        http.post()
            .uri("/internal/purchase-limit-checks")
            .headers(
                h -> {
                  h.setBearerAuth(ORDER);
                  h.set("X-Source-System", "cart-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .retrieve()
            .toEntity(String.class);
    assertThat(noKey.getStatusCode().value()).isEqualTo(200);
    Res over =
        send(
            ORDER,
            "/internal/purchase-limit-checks",
            Map.of("items", List.of(Map.of("sku", sku, "quantity", 11))));
    assertThat(over.body().path("passed").asBoolean()).isFalse();
    assertThat(over.body().path("violations").path(0).path("totalUnits").asInt()).isEqualTo(11);

    // 3) 판매 상태: MD 만, 일시품절은 바로, 판매중지는 승인 필요·승인은 한 번만
    String path = "/products/" + productId + "/sale-state-changes";
    assertThat(
            send(CLERK, path, Map.of("saleState", "TEMPORARILY_SOLD_OUT", "reason", "x")).status())
        .isEqualTo(403);
    Res soldOut = post(path, Map.of("saleState", "TEMPORARILY_SOLD_OUT", "reason", "입고 지연"));
    assertThat(soldOut.status()).isEqualTo(200);
    assertThat(soldOut.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(get("/products/" + productId).body().path("saleState").asString())
        .isEqualTo("TEMPORARILY_SOLD_OUT");
    String changed =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'ProductSaleStateChanged'"
                + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            productId);
    assertThat(mapper.readTree(changed).path("payload").path("state").asString())
        .isEqualTo("TEMP_OUT");

    Res noApproval = post(path, Map.of("saleState", "SALE_STOPPED", "reason", "단종"));
    assertThat(noApproval.status()).isEqualTo(422);
    assertThat(noApproval.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");

    String stop = saleStopApproval(productId);
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_state_grant WHERE approval_id = ?", Integer.class, stop)
            == 0) {
      Thread.sleep(200);
    }
    Res stopped =
        post(path, Map.of("saleState", "SALE_STOPPED", "reason", "단종", "approvalId", stop));
    assertThat(stopped.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(get("/products/" + productId).body().path("saleState").asString())
        .isEqualTo("SALE_STOPPED");
    Res reuse =
        post(path, Map.of("saleState", "SALE_STOPPED", "reason", "재사용", "approvalId", stop));
    assertThat(reuse.status()).isEqualTo(409);

    String requestId = soldOut.body().path("requestId").asString();
    assertThat(get(path + "/" + requestId).body().path("status").asString()).isEqualTo("APPLIED");
  }
}
