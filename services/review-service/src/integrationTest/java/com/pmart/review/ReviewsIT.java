package com.pmart.review;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.review.application.ReviewPhotoStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 * 리뷰 1차(0.2.0, ADR-0063): 배송 완료 → 자격, 사진 올리고 작성(ReviewPublished), 남의 품목·중복·전부 반품·기한 지남 거절, 수정, 삭제 뒤
 * 재작성 금지(ReviewDeleted), 공개 목록 요약, 사진은 공개 리뷰에 붙은 것만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.review.order-base-url=${order.url}",
      "pmart.review.customer-base-url=${customer.url}",
      "pmart.test.context=reviews"
    })
@Import({TestJwtConfiguration.class, ReviewsIT.MemoryPhotos.class})
@EnableWireMock({
  @ConfigureWireMock(name = "order", baseUrlProperties = "order.url"),
  @ConfigureWireMock(name = "customer", baseUrlProperties = "customer.url")
})
class ReviewsIT extends PostgresKafkaContainerSupport {

  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

  @TestConfiguration
  static class MemoryPhotos {
    @Bean
    @Primary
    ReviewPhotoStorage memoryReviewPhotoStorage() {
      return new ReviewPhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          STORED.put(key, bytes);
        }

        @Override
        public InputStream get(String key) {
          byte[] b = STORED.get(key);
          if (b == null) { // 실제 저장소와 같게(ReviewPhotoStorage 계약 — 없으면 404 PHOTO_NOT_FOUND)
            throw com.pmart.common.web.PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다.");
          }
          return new ByteArrayInputStream(b);
        }

        @Override
        public boolean delete(String key) {
          STORED.remove(key);
          return true;
        }
      };
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("customer")
  WireMockServer customer;

  record Res(int status, JsonNode body) {}

  Res send(String method, @Nullable String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Res read(int status, byte[] b) {
    JsonNode n;
    try {
      n = b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b);
    } catch (RuntimeException ex) {
      n = mapper.createObjectNode().put("raw", b.length);
    }
    return new Res(status, n);
  }

  Res upload(String token, byte[] bytes) {
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "p.jpg";
          }
        });
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .build()
        .post()
        .uri("http://localhost:" + port + "/api/v1/review-photos")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "customer-app");
            })
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  /** 머리말만 있는 20000×20000 PNG(몇십 바이트, 다 풀면 1.6GB). */
  static byte[] pngBomb() {
    java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(17);
    ihdr.put("IHDR".getBytes()).putInt(20000).putInt(20000).put((byte) 8).put((byte) 2);
    ihdr.put((byte) 0).put((byte) 0).put((byte) 0);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(ihdr.array());
    return java.nio.ByteBuffer.allocate(8 + 4 + 17 + 4)
        .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})
        .putInt(13)
        .put(ihdr.array())
        .putInt((int) crc.getValue())
        .array();
  }

  /** order 내부 주문(품목 두 개). returned = 첫 품목 반품 수량. */
  void stubOrder(String orderId, String account, String productId, int returned) {
    order.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%1$s","accountId":"%2$s","lines":[
                          {"orderLineId":"%1$s-L1","sku":"E-00000001","productId":"%3$s","productName":"사과","deliveredQty":0,"returnedQty":%4$d},
                          {"orderLineId":"%1$s-L2","sku":"E-00000002","productId":"%3$s","productName":"배","deliveredQty":0,"returnedQty":0}
                        ]}"""
                            .formatted(orderId, account, productId, returned))));
    customer.stubFor(
        get(urlPathMatching("/api/v1/internal/accounts/.*/review-author"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accountId\":\"x\",\"displayName\":\"장***\"}")));
  }

  void delivered(String orderId, Instant at, String... lineIds) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", "DLV-" + orderId);
    p.put("orderId", orderId);
    p.put("riderId", "RD-1");
    p.put(
        "lines",
        java.util.Arrays.stream(lineIds).map(l -> Map.of("orderLineId", l, "qty", 1)).toList());
    p.put("method", "CONTACTLESS");
    p.put("evidenceRef", "ev-1");
    p.put("deliveredAt", at.toString());
    kafka
        .send(
            "delivery.delivery.v1",
            "DLV-" + orderId,
            EventEnvelopeFixtures.envelope("DeliveryCompleted")
                .from("delivery-execution-service")
                .aggregate("Delivery", "DLV-" + orderId, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  String newId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void deliveredLineCanBeReviewedOnceWithPhotos() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, productId, 0);
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");

    Res el =
        await(
            () -> send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null),
            r -> r.body().path("items").size() == 2);
    assertThat(el.body().path("items").get(0).path("state").asString()).isEqualTo("REV_ELIGIBLE");
    assertThat(el.body().path("items").get(0).path("productName").asString()).isIn("사과", "배");

    // 사진: 형식이 틀리면 422, 맞으면 201
    assertThat(upload(token, "GIF89a".getBytes()).status()).isEqualTo(422);
    // 0.4.1: 화소 수 상한(작은 파일로 거대한 그림) — 422
    Res bomb = upload(token, pngBomb());
    assertThat(bomb.status()).isEqualTo(422);
    assertThat(bomb.body().path("code").asString()).isEqualTo("PHOTO_INVALID");
    Res photo = upload(token, JPEG);
    assertThat(photo.status()).isEqualTo(201);
    String photoId = photo.body().path("photoId").asString();
    // 붙기 전 사진은 공개되지 않는다
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(404);

    // 남의 사진은 못 붙인다
    String other = TestJwt.customer("ACC-other-" + newId());
    String otherPhoto = upload(other, JPEG).body().path("photoId").asString();
    Res bad =
        send(
            "POST",
            token,
            "/reviews",
            Map.of("orderLineId", orderId + "-L1", "rating", 5, "photoIds", List.of(otherPhoto)));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("PHOTO_NOT_FOUND");

    Res created =
        send(
            "POST",
            token,
            "/reviews",
            Map.of(
                "orderLineId",
                orderId + "-L1",
                "rating",
                5,
                "text",
                "  신선해요  ",
                "photoIds",
                List.of(photoId)));
    assertThat(created.status()).isEqualTo(201);
    String reviewId = created.body().path("reviewId").asString();
    assertThat(created.body().path("state").asString()).isEqualTo("PUBLISHED");
    assertThat(created.body().path("text").asString()).isEqualTo("신선해요");
    assertThat(created.body().path("authorMasked").asString()).isEqualTo("장***");
    assertThat(created.body().path("photos").get(0).path("url").asString())
        .isEqualTo("/api/v1/review-photos/" + photoId);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ReviewPublished'",
                reviewId))
        .isEqualTo(1);

    // 공개: 사진 본문·단건·목록 요약
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(200);
    // 0.4.0 축소본: 풀 수 없는 시험 조각은 원본 그대로, 허용 밖 폭은 400
    Res small = send("GET", null, "/review-photos/" + photoId + "?w=240", null);
    assertThat(small.status()).isEqualTo(200);
    assertThat(small.body().path("raw").asInt()).isEqualTo(JPEG.length);
    assertThat(send("GET", null, "/review-photos/" + photoId + "?w=1000", null).status())
        .isEqualTo(400);
    assertThat(send("GET", null, "/reviews/" + reviewId, null).status()).isEqualTo(200);
    Res list = send("GET", null, "/products/" + productId + "/reviews?withPhotoOnly=true", null);
    assertThat(list.body().path("items").size()).isEqualTo(1);
    assertThat(list.body().path("summary").path("reviewCount").asInt()).isEqualTo(1);
    assertThat(list.body().path("summary").path("averageRating").asDouble()).isEqualTo(5.0);

    // 같은 품목 두 번째 → ALREADY_REVIEWED, 남의 품목 → NOT_ELIGIBLE
    assertThat(
            send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("ALREADY_REVIEWED");
    assertThat(
            send("POST", other, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_ELIGIBLE");

    // 수정(별점 3, 사진 빼기) → 버전 2, ReviewPublished 한 번 더, 떨어진 사진은 비공개
    Res updated =
        send("PATCH", token, "/reviews/" + reviewId, Map.of("rating", 3, "photoIds", List.of()));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("rating").asInt()).isEqualTo(3);
    assertThat(updated.body().path("version").asInt()).isEqualTo(2);
    assertThat(updated.body().path("photos").size()).isZero();
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(404);
    // 남이 고치려 하면 404
    assertThat(send("PATCH", other, "/reviews/" + reviewId, Map.of("rating", 1)).status())
        .isEqualTo(404);
    // 토큰 없이 고치기는 403
    assertThat(send("PATCH", null, "/reviews/" + reviewId, Map.of("rating", 1)).status())
        .isIn(401, 403);

    // 삭제 → 204, 다시 삭제도 204, 공개 404, 재작성 금지, ReviewDeleted 한 번
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("GET", null, "/reviews/" + reviewId, null).status()).isEqualTo(404);
    assertThat(
            send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 5))
                .body()
                .path("code")
                .asString())
        .isEqualTo("REWRITE_AFTER_DELETE_FORBIDDEN");
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ReviewDeleted'",
                reviewId))
        .isEqualTo(1);
    Res el2 =
        send("GET", token, "/me/review-eligibilities?orderId=" + orderId + "&state=DELETED", null);
    assertThat(el2.body().path("items").size()).isEqualTo(1);
    assertThat(el2.body().path("items").get(0).path("reviewId").asString()).isEqualTo(reviewId);
  }

  @Test
  void fullyReturnedAndExpiredLinesCannotBeReviewed() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, "PRD-" + newId(), 1); // L1 은 받은 1개가 반품됨
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 2);

    Res returned =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 2));
    assertThat(returned.status()).isEqualTo(422);
    assertThat(returned.body().path("code").asString()).isEqualTo("NOT_ELIGIBLE");

    // 수령 15일 전으로 돌리면 기한 지남
    jdbc.update(
        "UPDATE review_eligibility SET delivered_at = ? WHERE order_line_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(15))),
        orderId + "-L2");
    Res expired =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 4));
    assertThat(expired.body().path("code").asString()).isEqualTo("ELIGIBILITY_EXPIRED");
    Res el =
        send("GET", token, "/me/review-eligibilities?orderId=" + orderId + "&state=EXPIRED", null);
    assertThat(el.body().path("items").size()).isEqualTo(1);
  }

  /**
   * 내부 조회 — 주문의 리뷰 작성 여부(0.3.0, 2026-11-18 리뷰 요청 알림): 토큰 없이, 쓰기 전 0·한 품목 쓰면 reviewed(삭제해도 그대로), 남의
   * 계정·없는 주문은 모두 0, 사용자 토큰은 403.
   */
  @Test
  void orderReviewStatusForNotification() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, "PRD-" + newId(), 0);
    delivered(orderId, Instant.now().minus(Duration.ofHours(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 2);
    String path = "/internal/orders/" + orderId + "/review-status?accountId=" + me;

    Res before = send("GET", null, path, null);
    assertThat(before.status()).isEqualTo(200);
    assertThat(before.body().path("eligibleLineCount").asInt()).isEqualTo(2);
    assertThat(before.body().path("reviewedLineCount").asInt()).isZero();
    assertThat(before.body().path("openLineCount").asInt()).isEqualTo(2);
    assertThat(before.body().path("reviewed").asBoolean()).isFalse();

    String reviewId =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 4))
            .body()
            .path("reviewId")
            .asString();
    Res after = send("GET", null, path, null);
    assertThat(after.body().path("reviewedLineCount").asInt()).isEqualTo(1);
    assertThat(after.body().path("openLineCount").asInt()).isEqualTo(1);
    assertThat(after.body().path("reviewed").asBoolean()).isTrue();

    // 삭제해도 쓴 것으로 센다(삭제 뒤 재작성 불가)
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("GET", null, path, null).body().path("reviewed").asBoolean()).isTrue();

    // 남의 계정·없는 주문은 0
    Res other =
        send(
            "GET",
            null,
            "/internal/orders/" + orderId + "/review-status?accountId=ACC-x-" + newId(),
            null);
    assertThat(other.status()).isEqualTo(200);
    assertThat(other.body().path("eligibleLineCount").asInt()).isZero();
    assertThat(
            send("GET", null, "/internal/orders/ORD-none/review-status?accountId=" + me, null)
                .body()
                .path("eligibleLineCount")
                .asInt())
        .isZero();
    // 사용자 토큰으로 내부 경로는 403
    assertThat(send("GET", token, path, null).status()).isEqualTo(403);
  }
}
