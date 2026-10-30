package com.pmart.product.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 등록 흐름(임시 백오피스): seed-v1 묶음 업로드(이미지 → SeaweedFS) → 재업로드 멱등 → ProductVersionPublished(sources)
 * 로 상품 연결 → 제출 준비 → ApprovalDecided(DISPLAY) → 게시본 조회·이미지 본문·ProductContentPublished(Outbox). 실제
 * PostgreSQL·Kafka·SeaweedFS, 시험 토큰. 이미지는 합성 바이트만 쓴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class ContentRegistrationFlowIT extends ContentContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String CUSTOMER = TestJwt.customer("c-1");

  static S3Client s3;

  RestClient http;

  @BeforeAll
  static void s3Client() {
    s3 =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_KEY, S3_SECRET)))
            .forcePathStyle(true)
            .endpointOverride(URI.create(s3Endpoint()))
            .build();
  }

  @AfterAll
  static void closeS3() {
    s3.close();
  }

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

  /** token 이 null 이면 비회원(토큰 없음). */
  Res get(String path, String token) {
    var e =
        http.get()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String path) {
    return get(path, MD);
  }

  record Bytes(int status, byte[] body, String contentType) {}

  Bytes image(String url, String token) {
    var e =
        RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .get()
            .uri("http://localhost:" + port + url)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Accept", "*/*");
                })
            .retrieve()
            .toEntity(byte[].class);
    return new Bytes(
        e.getStatusCode().value(), e.getBody(), String.valueOf(e.getHeaders().getContentType()));
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

  /** product-service 가 내는 ProductVersionPublished 를 흉내 낸다(sources 로 초안 연결). */
  void productPublished(String productId, int version, String sku, String sourceKey) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("baseProductId", "BP-" + productId);
    payload.put(
        "skus",
        List.of(
            Map.of(
                "sku", sku, "unitType", "E", "unitsPerSku", 1, "weightG", 180, "state", "ACTIVE")));
    payload.put("version", version);
    payload.put("saleState", "ON_SALE");
    payload.put("primaryCategoryId", "p03-c02");
    payload.put("categoryPaths", List.of("p03/p03-c02"));
    payload.put("effectiveAt", Instant.now().toString());
    if (sourceKey != null) {
      payload.put("sources", List.of(Map.of("system", "bmart-capture", "key", sourceKey)));
    }
    String json =
        EventEnvelopeFixtures.envelope("ProductVersionPublished")
            .from("product-service")
            .aggregate("Product", productId, version)
            .payload(payload)
            .buildJson();
    kafka.send("commerce.product.v1", productId, json).join();
  }

  /** approval-service 가 내는 ApprovalDecided 를 흉내 낸다(제출 준비가 돌려준 본문의 행 그대로, 필요하면 해시를 바꿔). */
  void decide(JsonNode approvalRequest, String decision, boolean tamperHash) {
    String approvalId = "AP-" + UUID.randomUUID();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (JsonNode l : approvalRequest.path("lines")) {
      Map<String, Object> attrs = mapper.convertValue(l.path("attributes"), Map.class);
      if (tamperHash) {
        attrs.put("draftHash", "0".repeat(64));
      }
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineId", l.path("lineKey").asString());
      line.put("decision", decision);
      line.put("targetRef", l.path("targetRef").asString());
      line.put("attributes", attrs);
      lines.add(line);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + UUID.randomUUID());
    payload.put("type", approvalRequest.path("type").asString());
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Display",
            "targetId",
            approvalRequest.path("targetRef").asString(),
            "targetService",
            approvalRequest.path("targetService").asString()));
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
  }

  /** 합성 JPEG(시그니처 + 표식 바이트). 실제 이미지가 아니다. */
  static byte[] jpeg(String marker) {
    byte[] m = marker.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[4 + m.length];
    out[0] = (byte) 0xFF;
    out[1] = (byte) 0xD8;
    out[2] = (byte) 0xFF;
    out[3] = (byte) 0xE0;
    System.arraycopy(m, 0, out, 4, m.length);
    return out;
  }

  byte[] seedZip(Map<String, Map<String, Object>> products, Map<String, Map<String, byte[]>> images)
      throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("seed-v1/manifest.json"));
      zip.write("{\"format\":\"seed-v1\"}".getBytes(StandardCharsets.UTF_8));
      for (var e : products.entrySet()) {
        String dir = "seed-v1/products/" + e.getKey() + "/";
        zip.putNextEntry(new ZipEntry(dir + "product.json"));
        zip.write(mapper.writeValueAsBytes(e.getValue()));
        for (var img : images.getOrDefault(e.getKey(), Map.of()).entrySet()) {
          zip.putNextEntry(new ZipEntry(dir + img.getKey()));
          zip.write(img.getValue());
        }
      }
    }
    return out.toByteArray();
  }

  static Map<String, Object> seedProduct(String id, String name) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("name", name);
    content.put("options", List.of(Map.of("label", "1개", "capacityLabel", "180g")));
    content.put("description", "OCR 초안 설명");
    content.put("storageGuide", "냉동 보관");
    content.put("returnGuide", null);
    content.put("notices", List.of("원산지: 국내산", "용량: 180g"));
    content.put("rating", null);
    return Map.of(
        "format",
        "seed-v1",
        "product",
        Map.of("seedProductId", id, "internalName", name),
        "content",
        content,
        "source",
        Map.of("system", "bmart-capture"));
  }

  LinkedMultiValueMap<String, Object> file(byte[] bytes, String name) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return name;
          }
        });
    return form;
  }

  int objectCount() {
    return s3.listObjectsV2Paginator(b -> b.bucket(BUCKET)).contents().stream()
        .mapToInt(o -> 1)
        .sum();
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void seedBatchLinksPublishesAndServesImages() throws Exception {
    String run = UUID.randomUUID().toString().substring(0, 8);
    String a = "bm-a-" + run;
    String b = "bm-b-" + run;
    byte[] main = jpeg("main-" + run);
    byte[] d1 = jpeg("desc1-" + run);
    byte[] d2 = jpeg("desc2-" + run);
    byte[] info = jpeg("info-" + run);
    byte[] zip =
        seedZip(
            Map.of(a, seedProduct(a, "[냉동]호밍스 부대찌개 180g"), b, seedProduct(b, "대표 사진 없는 상품")),
            Map.of(
                a,
                Map.of(
                    "images/main.jpg",
                    main,
                    "images/description_01.jpg",
                    d1,
                    "images/description_02.jpg",
                    d2,
                    "images/info.jpg",
                    info),
                b,
                Map.of("images/description_01.jpg", d1))); // 같은 내용 → 저장소에 다시 올리지 않음
    int objectsBefore = objectCount();

    // 1) 업로드: 초안 2건, 이미지 5장 중 저장소에는 서로 다른 4개만
    Res batch = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(batch.status()).isEqualTo(201);
    assertThat(batch.body().path("createdCount").asInt()).isEqualTo(2);
    assertThat(batch.body().path("imageCount").asInt()).isEqualTo(5);
    assertThat(batch.body().path("uploadedImageCount").asInt()).isEqualTo(4);
    assertThat(objectCount() - objectsBefore).isEqualTo(4);
    JsonNode draftA = null;
    JsonNode draftB = null;
    for (JsonNode item : batch.body().path("items")) {
      if (a.equals(item.path("sourceKey").asString())) {
        draftA = item.path("draft");
      } else {
        draftB = item.path("draft");
      }
    }
    String draftAId = draftA.path("draftId").asString();
    String draftBId = draftB.path("draftId").asString();
    assertThat(draftA.path("images")).hasSize(4);
    assertThat(draftA.path("images").path(0).path("role").asString()).isEqualTo("MAIN");
    assertThat(draftA.path("input").path("notices").path(0).asString()).isEqualTo("원산지: 국내산");
    assertThat(draftA.path("submittable").asBoolean()).isFalse();
    assertThat(draftA.path("problems").toString()).contains("PRODUCT_NOT_LINKED");
    assertThat(draftB.path("problems").toString()).contains("MAIN_IMAGE_REQUIRED");
    String objectKey =
        jdbc.queryForObject(
            "SELECT object_key FROM content_image WHERE draft_id = ? AND role = 'MAIN'",
            String.class,
            draftAId);
    assertThat(objectKey).startsWith("products/draft-" + draftAId + "/main/").endsWith(".jpg");
    assertThat(s3.headObject(h -> h.bucket(BUCKET).key(objectKey)).contentLength())
        .isEqualTo(main.length);

    // 2) 상품 승인 전에는 제출할 수 없다
    Res early = post("/content-drafts/" + draftAId + "/submissions", null);
    assertThat(early.status()).isEqualTo(422);
    assertThat(early.body().path("code").asString()).isEqualTo("PRODUCT_NOT_LINKED");

    // 3) 같은 묶음을 다시 올려도 초안·이미지·저장소 객체가 늘지 않는다
    int draftsBefore = count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b);
    int imagesBefore =
        count("SELECT COUNT(*) FROM content_image WHERE draft_id IN (?, ?)", draftAId, draftBId);
    Res again = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("unchangedCount").asInt()).isEqualTo(2);
    assertThat(again.body().path("uploadedImageCount").asInt()).isZero();
    assertThat(count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b))
        .isEqualTo(draftsBefore)
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM content_image WHERE draft_id IN (?, ?)", draftAId, draftBId))
        .isEqualTo(imagesBefore)
        .isEqualTo(5);
    assertThat(objectCount() - objectsBefore).isEqualTo(4);

    // 4) 상품 승인(ProductVersionPublished, sources) → 원천 키로 연결
    String productId = "PRD-" + UUID.randomUUID();
    String sku = String.format("E-%08d", Math.abs(run.hashCode() % 100_000_000));
    productPublished(productId, 1, sku, a);
    JsonNode linked =
        await("/content-drafts/" + draftAId, x -> productId.equals(x.path("productId").asString()))
            .body();
    assertThat(linked.path("productId").asString()).isEqualTo(productId);
    assertThat(linked.path("submittable").asBoolean()).isTrue();

    // 5) 배치 제출 준비 → DISPLAY 승인 본문, 대표 사진 없는 b 는 빠진다
    String againBatch = again.body().path("batchId").asString();
    Res submission = post("/content-import-batches/" + againBatch + "/submissions", null);
    assertThat(submission.status()).isEqualTo(200);
    assertThat(submission.body().path("submittedDraftIds").path(0).asString()).isEqualTo(draftAId);
    assertThat(submission.body().path("excludedDraftIds").path(0).asString()).isEqualTo(draftBId);
    JsonNode body = submission.body().path("approvalRequest");
    assertThat(body.path("type").asString()).isEqualTo("DISPLAY");
    assertThat(body.path("targetService").asString()).isEqualTo("product-content-service");
    assertThat(body.path("targetRef").asString()).isEqualTo("contentImportBatch:" + againBatch);
    assertThat(body.path("lines").path(0).path("attributes").path("draftHash").asString())
        .hasSize(64);
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);

    // 6) 승인 → 게시
    decide(body, "APPROVED", false);
    JsonNode published =
        await("/content-drafts/" + draftAId, x -> "PUBLISHED".equals(x.path("state").asString()))
            .body();
    assertThat(published.path("state").asString()).isEqualTo("PUBLISHED");
    assertThat(published.path("contentVersion").asInt()).isEqualTo(1);
    assertThat(
            await(
                    "/content-import-batches/" + againBatch,
                    x -> "PUBLISHED".equals(x.path("state").asString()))
                .body()
                .path("state")
                .asString())
        .isEqualTo("PUBLISHED");

    Res content = get("/product-contents/" + productId, CUSTOMER);
    assertThat(content.status()).isEqualTo(200);
    JsonNode c = content.body();
    assertThat(c.path("name").asString()).isEqualTo("[냉동]호밍스 부대찌개 180g");
    assertThat(c.path("contentVersion").asInt()).isEqualTo(1);
    assertThat(c.path("productVersion").asInt()).isEqualTo(1);
    assertThat(c.path("images")).hasSize(4);
    assertThat(c.path("images").path(1).path("role").asString()).isEqualTo("DESCRIPTION");
    assertThat(c.path("categoryPath").toString()).isEqualTo("[\"p03\",\"p03-c02\"]");
    assertThat(c.path("options").path(0).path("sku").asString()).isEqualTo(sku);
    assertThat(c.path("options").path(0).path("capacityLabel").asString()).isEqualTo("180g");
    // 가격·가용은 pricing·inventory 사본을 아직 받지 않으므로 값을 채우지 않는다(null/없음)
    JsonNode opt = c.path("options").path(0);
    assertThat(opt.path("price").isNull() || opt.path("price").isMissingNode()).isTrue();
    assertThat(opt.path("availability").isNull() || opt.path("availability").isMissingNode())
        .isTrue();
    assertThat(c.path("rating").isMissingNode() || c.path("rating").isNull()).isTrue();
    String mainUrl = c.path("mainImage").path("url").asString();
    assertThat(mainUrl).startsWith("/api/v1/content-images/IMG-");

    Res cards = get("/product-contents?productIds=" + productId + ",PRD-none", CUSTOMER);
    assertThat(cards.body().path("items")).hasSize(1);
    assertThat(cards.body().path("missing").path(0).asString()).isEqualTo("PRD-none");

    // 7) 이미지 본문: 고객은 게시된 이미지만, 운영자는 초안 이미지도
    Bytes served = image(mainUrl, CUSTOMER);
    assertThat(served.status()).isEqualTo(200);
    assertThat(served.body()).isEqualTo(main);
    assertThat(served.contentType()).isEqualTo("image/jpeg");
    String draftBImage = draftB.path("images").path(0).path("url").asString();
    assertThat(image(draftBImage, CUSTOMER).status()).isEqualTo(404);
    // 비회원(토큰 없음)도 게시본만: 상세·카드·게시 이미지 200, 초안 이미지 404
    assertThat(get("/product-contents/" + productId, null).status()).isEqualTo(200);
    assertThat(get("/product-contents?productIds=" + productId, null).body().path("items"))
        .hasSize(1);
    assertThat(image(mainUrl, null).body()).isEqualTo(main);
    assertThat(image(draftBImage, null).status()).isEqualTo(404);
    assertThat(image(draftBImage, MD).body()).isEqualTo(d1);

    // 8) 발행: ProductContentPublished 가 commerce.product.v1 로 Outbox 에
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ProductContentPublished'"
                    + " AND aggregateid = ? AND topic = 'commerce.product.v1'",
                productId))
        .isEqualTo(1);

    // 9) 게시 후 같은 묶음을 다시 올려도 새 초안이 생기지 않는다(바뀐 것 없음)
    Res third = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(third.body().path("unchangedCount").asInt()).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b))
        .isEqualTo(2);
  }

  @Test
  void singleDraftImageRulesScopeMismatchAndRejection() throws Exception {
    String productId = "PRD-" + UUID.randomUUID();
    productPublished(productId, 3, "E-99999999", null);
    // 투영이 생길 때까지 기다린다(명시 productId 는 수신한 상품이어야 한다)
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (count("SELECT COUNT(*) FROM product_projection WHERE product_id = ?", productId) == 0
        && Instant.now().isBefore(until)) {
      Thread.sleep(200);
    }
    assertThat(
            post("/content-drafts", Map.of("name", "x", "productId", "PRD-unknown"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("PRODUCT_NOT_FOUND");

    Res created =
        post(
            "/content-drafts",
            Map.of("productId", productId, "name", "삼다수 500mL", "notices", List.of("용량: 500mL")));
    assertThat(created.status()).isEqualTo(201);
    String draftId = created.body().path("draftId").asString();
    assertThat(created.body().path("problems").toString()).contains("MAIN_IMAGE_REQUIRED");

    // 이미지 규칙: 형식·MAIN 자리
    var notImage = file("not an image".getBytes(StandardCharsets.UTF_8), "a.txt");
    notImage.add("role", "MAIN");
    assertThat(
            post("/content-drafts/" + draftId + "/images", notImage).body().path("code").asString())
        .isEqualTo("IMAGE_INVALID");
    var secondMain = file(jpeg("m2"), "m.jpg");
    secondMain.add("role", "MAIN");
    secondMain.add("sortOrder", "1");
    assertThat(post("/content-drafts/" + draftId + "/images", secondMain).status()).isEqualTo(422);

    var mainForm = file(jpeg("water-" + productId), "main.jpg");
    mainForm.add("role", "MAIN");
    Res uploaded = post("/content-drafts/" + draftId + "/images", mainForm);
    assertThat(uploaded.status()).isEqualTo(201);
    assertThat(uploaded.body().path("sha256").asString()).hasSize(64);
    assertThat(
            jdbc.queryForObject(
                "SELECT object_key FROM content_image WHERE image_id = ?",
                String.class,
                uploaded.body().path("imageId").asString()))
        .startsWith("products/" + productId + "/main/");

    // 잠근 해시와 다른 승인은 반영하지 않는다
    Res sub = post("/content-drafts/" + draftId + "/submissions", null);
    assertThat(sub.status()).isEqualTo(200);
    assertThat(sub.body().path("approvalRequest").path("targetRef").asString())
        .isEqualTo("contentDraft:" + draftId);
    assertThat(
            post("/content-drafts/" + draftId + "/images", mainForm).body().path("code").asString())
        .isEqualTo("DRAFT_NOT_EDITABLE");
    decide(sub.body().path("approvalRequest"), "APPROVED", true);
    JsonNode mismatch =
        await("/content-drafts/" + draftId, x -> "REJECTED".equals(x.path("state").asString()))
            .body();
    assertThat(mismatch.path("problems").toString()).contains("APPROVAL_SCOPE_MISMATCH");
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);

    // 반려된 초안도 게시하지 않는다
    Res second = post("/content-drafts", Map.of("productId", productId, "name", "삼다수"));
    String secondId = second.body().path("draftId").asString();
    var m = file(jpeg("water2-" + productId), "main.jpg");
    m.add("role", "MAIN");
    post("/content-drafts/" + secondId + "/images", m);
    decide(
        post("/content-drafts/" + secondId + "/submissions", null).body().path("approvalRequest"),
        "REJECTED",
        false);
    JsonNode rejected =
        await("/content-drafts/" + secondId, x -> "REJECTED".equals(x.path("state").asString()))
            .body();
    assertThat(rejected.path("problems").toString()).contains("APPROVAL_REJECTED");
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);
    assertThat(get("/content-drafts/" + secondId, CUSTOMER).status()).isEqualTo(403);
  }
}
