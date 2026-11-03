package com.pmart.product;

import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(okDraft.path("input").path("derivedFields").toString()).contains("alcohol");

    // 3) 제출 준비 → 승인
    Res submission = post("/product-import-batches/" + batchId + "/submissions", null);
    assertThat(submission.status()).isEqualTo(200);
    assertThat(submission.body().path("submittedDraftIds")).hasSize(1);
    assertThat(submission.body().path("excludedDraftIds")).hasSize(1);
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
    JsonNode links =
        send(
                ORDER,
                "/internal/source-link-lookups",
                Map.of("sourceSystem", "bmart-capture", "sourceKeys", List.of("bm-ok", "bm-none")))
            .body();
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
    Res again = post("/product-import-batches", form(zip));
    String againBatch = again.body().path("batchId").asString();
    decide(
        post("/product-import-batches/" + againBatch + "/submissions", null)
            .body()
            .path("approvalRequest"),
        "APPROVED");
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
    String barcode = "0" + (System.nanoTime() % 1_000_000_000_000L);

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
    Res c = post("/product-drafts", water("9" + barcode));
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
    Res a = post("/product-drafts", water("1" + (System.nanoTime() % 1_000_000_000_000L)));
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
