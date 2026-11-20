package com.pmart.storefront;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 상품·콘텐츠·판매 상태·가격 사건(Kafka) → 진열 투영(PostgreSQL) → 홈·섹션 API(토큰 없이). 공개 카드 API 는 WireMock. 같은 사건 재전달은
 * inbox, 늦게 온 옛 사건은 버전·시각 비교로 무시한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      // 순위 신호 사건(ADR-0072)을 outbox 에서 확인한다
      "pmart.messaging.outbox.delete-after-insert=false",
      // 순위 신호(ADR-0072) — 주문 사건 구독·정책값. LAN 설정(pmart-config)에는 배포 승인 때 같은 값을 넣는다(시험 사본 = 정본 유지)
      "spring.cloud.function.definition=catalogEvents;handlingEvents;reviewEvents;orderEvents;approvalEvents;promotionEvents",
      "spring.cloud.stream.bindings.orderEvents-in-0.destination=order.order.v1",
      "pmart.storefront.ranking.sales-window-days=30",
      "pmart.storefront.ranking.rating-prior-count=5",
      "pmart.storefront.ranking.rating-prior-mean=4.0"
    })
@EnableWireMock(
    @ConfigureWireMock(name = "content", baseUrlProperties = "pmart.storefront.content-base-url"))
class HomeFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("content")
  WireMockServer content;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    content.resetAll();
    for (int i = 1; i <= 12; i++) {
      card("PRD-H" + i, "[시험] 상품 " + i, 1);
    }
  }

  void card(String productId, String name, int version) {
    content.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/product-contents"))
            .withQueryParam("productIds", equalTo(productId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    Map.of(
                                        "productId", productId,
                                        "name", name,
                                        "contentVersion", version,
                                        "mainImage",
                                            Map.of(
                                                "url",
                                                "/api/v1/content-images/IMG-" + productId))))))));
  }

  void send(String type, String id, long version, Map<String, Object> payload) {
    String topic =
        type.equals("PriceVersionEffective")
            ? "commerce.pricing.v1"
            : type.equals("SkuHandlingChanged") ? "mfc.sku-handling.v1" : "commerce.product.v1";
    kafka
        .send(
            topic,
            id,
            EventEnvelopeFixtures.envelope(type)
                .from("it")
                .aggregate(
                    type.equals("PriceVersionEffective")
                        ? "PriceVersion"
                        : type.equals("SkuHandlingChanged") ? "SkuHandling" : "Product",
                    id,
                    version)
                .payload(payload)
                .buildJson())
        .join();
  }

  void product(String id, int version, String sku) {
    send(
        "ProductVersionPublished",
        id,
        version,
        Map.of(
            "productId",
            id,
            "version",
            version,
            "saleState",
            "ON_SALE",
            "categoryIds",
            List.of("t01-c01"),
            "skus",
            List.of(Map.of("sku", sku)),
            "effectiveAt",
            "2026-10-30T01:00:00Z"));
  }

  /** 대표 SKU 에 내용량(2026-11-19 BR-19)이 있는 상품. */
  void productWithNetContent(String id, int version, String sku, int amount, String unit) {
    send(
        "ProductVersionPublished",
        id,
        version,
        Map.of(
            "productId",
            id,
            "version",
            version,
            "saleState",
            "ON_SALE",
            "categoryIds",
            List.of("t01-c01"),
            "skus",
            List.of(Map.of("sku", sku, "netContent", Map.of("amount", amount, "unit", unit))),
            "effectiveAt",
            "2026-10-30T01:00:00Z"));
  }

  void published(String id, int version, String at) {
    send(
        "ProductContentPublished",
        id,
        version,
        Map.of("productId", id, "contentVersion", version, "publishedAt", at));
  }

  JsonNode get(String path) {
    String body = http.get().uri(path).retrieve().body(String.class);
    return mapper.readTree(body == null ? "{}" : body);
  }

  int status(String path) {
    return http.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
  }

  static List<String> ids(JsonNode items) {
    List<String> ids = new ArrayList<>();
    items.forEach(i -> ids.add(i.path("productId").asString()));
    return ids;
  }

  JsonNode awaitHome(Predicate<JsonNode> until) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    JsonNode last = null;
    while (Instant.now().isBefore(deadline)) {
      last = get("/storefront/home");
      if (until.test(last)) {
        return last;
      }
      Thread.sleep(300);
    }
    throw new AssertionError("홈 조건 미충족: " + last);
  }

  @Test
  void homeShowsPublishedProductsNewestFirst() throws Exception {
    // 빈 투영이어도 홈은 200(섹션 OK, 상품 0)
    JsonNode empty = get("/storefront/home");
    assertThat(empty.path("layoutVersion").asInt()).isZero();
    assertThat(empty.path("sections").path(0).path("sectionId").asString()).isEqualTo("S-ALL");

    send(
        "PriceVersionEffective",
        "E-H1",
        1,
        Map.of(
            "sku",
            "E-H1",
            "price",
            1900,
            "priceVersion",
            1,
            "effectiveAt",
            "2026-10-30T00:00:00Z"));
    for (int i = 1; i <= 12; i++) {
      if (i == 1) {
        productWithNetContent("PRD-H1", 1, "E-H1", 500, "G"); // 내용량(BR-19)
      } else {
        product("PRD-H" + i, 1, "E-H" + i);
      }
      published("PRD-H" + i, 1, "2026-10-30T02:%02d:00Z".formatted(i));
    }
    product("PRD-NOCONTENT", 1, "E-X"); // 콘텐츠 없음 → 진열 안 함

    JsonNode home =
        awaitHome(
            h ->
                h.path("sections").path(0).path("items").size() == 10
                    && h.path("sections").path(0).path("nextCursor").isString()
                    // 12개 모두 반영(마지막에 게시한 H12 가 맨 앞)될 때까지 — 10개가 먼저 보일 수 있다
                    && "PRD-H12"
                        .equals(
                            h.path("sections")
                                .path(0)
                                .path("items")
                                .path(0)
                                .path("productId")
                                .asString()));
    JsonNode section = home.path("sections").path(0);
    assertThat(home.path("tabs").path(0).asString()).isEqualTo("HOME");
    assertThat(section.path("state").asString()).isEqualTo("OK");
    assertThat(section.path("kind").asString()).isEqualTo("THEME");
    assertThat(ids(section.path("items")).get(0)).isEqualTo("PRD-H12"); // 최근 게시 순

    // 섹션 다음 묶음(가격은 다른 토픽 — 반영될 때까지)
    String nextPath =
        "/storefront/sections/S-ALL/items?layoutVersion=0&size=10&cursor="
            + section.path("nextCursor").asString();
    JsonNode next = get(nextPath);
    for (int i = 0; i < 100 && next.path("items").path(1).path("basePrice").asInt() != 1900; i++) {
      Thread.sleep(300);
      next = get(nextPath);
    }
    assertThat(ids(next.path("items"))).containsExactly("PRD-H2", "PRD-H1");
    JsonNode h1 = next.path("items").path(1);
    assertThat(h1.path("basePrice").asInt()).as("상품보다 먼저 온 가격").isEqualTo(1900);
    // 단위 가격(BR-19): 1900원 × 100 ÷ 500g = 380원, 내용량 없는 상품은 없음
    assertThat(h1.path("netContent").path("amount").asInt()).isEqualTo(500);
    assertThat(h1.path("unitPrice").path("basis").asInt()).isEqualTo(100);
    assertThat(h1.path("unitPrice").path("unit").asString()).isEqualTo("G");
    assertThat(h1.path("unitPrice").path("price").asInt()).isEqualTo(380);
    JsonNode h2 = next.path("items").path(0);
    assertThat(h2.path("unitPrice").isNull() || h2.path("unitPrice").isMissingNode()).isTrue();
    assertThat(h1.path("name").asString()).isEqualTo("[시험] 상품 1");
    assertThat(h1.path("imageUrl").asString()).isEqualTo("/api/v1/content-images/IMG-PRD-H1");
    assertThat(next.path("page").path("nextCursor").isNull()).isTrue();

    // 판매중지 → 빠짐, 옛 시각의 상태·옛 콘텐츠 버전·같은 사건 재전달은 무시
    send(
        "ProductSaleStateChanged",
        "PRD-H12",
        2,
        Map.of(
            "productId", "PRD-H12",
            "sku", "E-H12",
            "state", "DISCONTINUED",
            "effectiveAt", "2026-10-30T03:00:00Z"));
    send(
        "ProductSaleStateChanged",
        "PRD-H12",
        1,
        Map.of(
            "productId", "PRD-H12",
            "sku", "E-H12",
            "state", "ON_SALE",
            "effectiveAt", "2026-10-30T02:59:00Z"));
    card("PRD-H11", "[시험] 이름 바뀐 상품 11", 2);
    published("PRD-H11", 2, "2026-10-30T04:00:00Z");
    published("PRD-H11", 1, "2026-10-30T02:11:00Z");
    JsonNode after =
        awaitHome(
            h ->
                !ids(h.path("sections").path(0).path("items")).contains("PRD-H12")
                    && h.path("sections")
                        .path(0)
                        .path("items")
                        .path(0)
                        .path("name")
                        .asString()
                        .contains("이름 바뀐"));
    assertThat(ids(after.path("sections").path(0).path("items")).get(0))
        .as("게시 시각은 처음 게시 기준")
        .isEqualTo("PRD-H11");
    assertThat(
            jdbc.queryForObject(
                "SELECT content_version FROM display_product WHERE product_id = 'PRD-H11'",
                Integer.class))
        .isEqualTo(2);

    // 2026-11-04: 판매 상태는 상품 행 버전으로 비교 — 시각이 더 이른 재적재(rowVersion 6)가 일시품절(rv 5)을 풀고,
    // 늦은 시각의 옛 행 버전(rv 5) 사건은 무시
    send(
        "ProductSaleStateChanged",
        "PRD-H11",
        5,
        Map.of(
            "productId",
            "PRD-H11",
            "sku",
            "E-H11",
            "state",
            "TEMP_OUT",
            "version",
            5,
            "effectiveAt",
            "2026-10-30T06:00:00Z"));
    awaitSaleState("PRD-H11", "TEMPORARILY_SOLD_OUT");
    send(
        "ProductVersionPublished",
        "PRD-H11",
        3,
        Map.of(
            "productId",
            "PRD-H11",
            "version",
            3,
            "rowVersion",
            6,
            "saleState",
            "ON_SALE",
            "categoryIds",
            List.of("t01-c01"),
            "skus",
            List.of(Map.of("sku", "E-H11")),
            "effectiveAt",
            "2026-10-30T05:00:00Z"));
    awaitSaleState("PRD-H11", "ON_SALE");
    send(
        "ProductSaleStateChanged",
        "PRD-H11",
        5,
        Map.of(
            "productId",
            "PRD-H11",
            "sku",
            "E-H11",
            "state",
            "TEMP_OUT",
            "version",
            5,
            "effectiveAt",
            "2026-10-30T07:00:00Z"));
    // 순서 표시: 같은 토픽·파티션의 다음 사건(콘텐츠 v3)이 반영되면 앞 사건도 처리됐다
    card("PRD-H11", "[시험] 이름 또 바뀐 상품 11", 3);
    published("PRD-H11", 3, "2026-10-30T07:01:00Z");
    for (int i = 0;
        i < 100
            && !Integer.valueOf(3)
                .equals(
                    jdbc.queryForObject(
                        "SELECT content_version FROM display_product WHERE product_id = 'PRD-H11'",
                        Integer.class));
        i++) {
      Thread.sleep(300);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT sale_state FROM display_product WHERE product_id = 'PRD-H11'",
                String.class))
        .as("옛 행 버전의 늦은 사건은 무시")
        .isEqualTo("ON_SALE");

    // 오류·미구성
    // 탭 정책값이 없으면 랭킹·신상품 탭은 없다(404 — ADR-0075, 이 시험 문맥은 tabs 설정 없음)
    assertThat(status("/storefront/tabs/RANKING")).isEqualTo(404);
    assertThat(status("/storefront/sections/S-NEW/items")).isEqualTo(404);
    assertThat(status("/storefront/sections/S-NONE/items")).isEqualTo(404);
    assertThat(status("/storefront/sections/S-ALL/items?layoutVersion=3")).isEqualTo(409);
    assertThat(status("/storefront/sections/S-ALL/items?cursor=garbage")).isEqualTo(400);
    assertThat(get("/storefront/banners").path("items")).isEmpty();
  }

  void awaitSaleState(String productId, String state) throws InterruptedException {
    for (int i = 0; i < 150; i++) {
      String now =
          jdbc.queryForObject(
              "SELECT sale_state FROM display_product WHERE product_id = ?",
              String.class,
              productId);
      if (state.equals(now)) {
        return;
      }
      Thread.sleep(300);
    }
    throw new AssertionError(productId + " 판매 상태가 " + state + " 가 되지 않음");
  }

  void handling(String mfcId, String sku, boolean enabled, int version) {
    send(
        "SkuHandlingChanged",
        mfcId + ":" + sku,
        version,
        Map.of(
            "mfcId", mfcId,
            "sku", sku,
            "handlingEnabled", enabled,
            "version", version,
            "changedAt", "2026-11-19T00:0" + version + ":00Z"));
  }

  void review(String type, String reviewId, String productId, int rating, int version) {
    kafka
        .send(
            "commerce.review.v1",
            reviewId,
            EventEnvelopeFixtures.envelope(type)
                .from("review-service")
                .aggregate("Review", reviewId, version)
                .payload(
                    Map.of(
                        "reviewId", reviewId,
                        "productId", productId,
                        "rating", rating,
                        "orderLineId", "OL-" + reviewId))
                .buildJson())
        .join();
  }

  /**
   * BR-63(0.4.0, 2026-11-19): 리뷰 사건 → 진열 항목 rating(평균, 소수 첫째)·reviewCount, 수정·삭제·옛 버전 무시, 리뷰 없으면
   * 0·null.
   */
  @Test
  void itemsShowReviewRating() throws Exception {
    card("PRD-R1", "[시험] 평점 상품", 1);
    card("PRD-R2", "[시험] 리뷰 없는 상품", 1);
    product("PRD-R1", 1, "E-R1");
    published("PRD-R1", 1, "2026-09-01T00:01:00Z"); // 다른 시험보다 뒤로
    product("PRD-R2", 1, "E-R2");
    published("PRD-R2", 1, "2026-09-01T00:00:30Z");
    review("ReviewPublished", "RV-R1", "PRD-R1", 5, 1);
    review("ReviewPublished", "RV-R2", "PRD-R1", 4, 1);
    review("ReviewPublished", "RV-R3", "PRD-R1", 1, 1);
    review("ReviewPublished", "RV-R2", "PRD-R1", 2, 2); // 수정 4 → 2
    review("ReviewPublished", "RV-R2", "PRD-R1", 4, 1); // 늦게 온 옛 버전 — 무시
    review("ReviewDeleted", "RV-R3", "PRD-R1", 1, 2); // 삭제
    String path = "/storefront/sections/S-ALL/items?layoutVersion=0&size=50";
    JsonNode r1 = null;
    JsonNode r2 = null;
    for (int i = 0; i < 100; i++) {
      JsonNode items = get(path).path("items");
      r1 = null;
      r2 = null;
      for (JsonNode it : items) {
        if ("PRD-R1".equals(it.path("productId").asString())) {
          r1 = it;
        }
        if ("PRD-R2".equals(it.path("productId").asString())) {
          r2 = it;
        }
      }
      if (r1 != null
          && r2 != null
          && r1.path("reviewCount").asInt() == 2
          && r1.path("rating").asDouble() == 3.5) {
        break;
      }
      Thread.sleep(300);
    }
    assertThat(r1).isNotNull();
    assertThat(r1.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(r1.path("rating").asDouble()).isEqualTo(3.5); // (5 + 2) / 2
    assertThat(r2.path("reviewCount").asInt()).isZero();
    assertThat(r2.path("rating").isNull() || r2.path("rating").isMissingNode()).isTrue();
    // 같은 DB 를 쓰는 다른 시험에 섞이지 않게 판매중지로 치운다
    for (String id : List.of("PRD-R1", "PRD-R2")) {
      send(
          "ProductSaleStateChanged",
          id,
          2,
          Map.of(
              "productId",
              id,
              "sku",
              id.equals("PRD-R1") ? "E-R1" : "E-R2",
              "state",
              "DISCONTINUED",
              "effectiveAt",
              "2026-11-19T06:00:00Z"));
    }
    for (int i = 0; i < 100 && ids(get(path).path("items")).contains("PRD-R1"); i++) {
      Thread.sleep(300);
    }
  }

  void discount(String sku, int seq, int priceVersion, long base, long unit, String endsAt) {
    Map<String, Object> p = new java.util.HashMap<>();
    p.put("sku", sku);
    p.put("seq", seq);
    p.put("priceVersion", priceVersion);
    p.put("basePrice", base);
    p.put("unitDiscount", unit);
    p.put("salePrice", base - unit);
    p.put("ratePercent", (int) (unit * 100 / base));
    p.put("promotionId", unit > 0 ? "PRM-D" : null);
    p.put("promotionName", unit > 0 ? "두부 할인" : null);
    p.put("endsAt", unit > 0 ? endsAt : null);
    kafka
        .send(
            "commerce.sku-display-discount.v1",
            sku,
            EventEnvelopeFixtures.envelope("SkuDisplayDiscountChanged")
                .from("promotion-service")
                .aggregate("SkuDisplayDiscount", sku, seq)
                .payload(p)
                .buildJson())
        .join();
  }

  JsonNode awaitItem(String productId, Predicate<JsonNode> ok) throws InterruptedException {
    String path = "/storefront/sections/S-ALL/items?layoutVersion=0&size=50";
    JsonNode last = null;
    for (int i = 0; i < 100; i++) {
      for (JsonNode it : get(path).path("items")) {
        if (productId.equals(it.path("productId").asString())) {
          last = it;
        }
      }
      if (last != null && ok.test(last)) {
        return last;
      }
      Thread.sleep(300);
    }
    throw new AssertionError("항목 조건 미충족: " + last);
  }

  /** ADR-0082 4단계(0.8.0): 표시 할인 사건 → 할인가. seq 역순 무시, 가격 버전이 다르면·끝났으면 정가. */
  @Test
  void itemsShowDisplayDiscount() throws Exception {
    card("PRD-D1", "[시험] 할인 상품", 1);
    product("PRD-D1", 1, "E-D1");
    published("PRD-D1", 1, "2026-09-01T00:02:00Z");
    send(
        "PriceVersionEffective",
        "E-D1",
        1,
        Map.of(
            "sku",
            "E-D1",
            "price",
            2000,
            "priceVersion",
            1,
            "effectiveAt",
            "2026-10-30T00:00:00Z"));
    discount("E-D1", 2, 1, 2000, 400, "2099-01-01T00:00:00Z");
    discount("E-D1", 1, 1, 2000, 100, "2099-01-01T00:00:00Z"); // 늦게 온 옛 seq — 무시
    JsonNode it = awaitItem("PRD-D1", n -> n.path("salePrice").asInt() == 1600);
    assertThat(it.path("basePrice").asInt()).isEqualTo(2000);
    assertThat(it.path("discountRatePercent").asInt()).isEqualTo(20);
    assertThat(it.path("discountName").asString()).isEqualTo("두부 할인");
    assertThat(it.path("discountEndsAt").asString()).startsWith("2099-01-01");
    // 가격이 바뀌었는데 할인 사건은 아직 옛 가격 버전 → 정가
    send(
        "PriceVersionEffective",
        "E-D1",
        2,
        Map.of(
            "sku",
            "E-D1",
            "price",
            2500,
            "priceVersion",
            2,
            "effectiveAt",
            "2026-10-31T00:00:00Z"));
    it = awaitItem("PRD-D1", n -> n.path("basePrice").asInt() == 2500);
    assertThat(it.path("salePrice").isNull() || it.path("salePrice").isMissingNode()).isTrue();
    discount("E-D1", 3, 2, 2500, 500, "2099-01-01T00:00:00Z");
    awaitItem("PRD-D1", n -> n.path("salePrice").asInt() == 2000);
    // 끝 시각이 지난 할인은 끝 사건 전에도 정가
    discount("E-D1", 4, 2, 2500, 500, "2020-01-01T00:00:00Z");
    awaitItem("PRD-D1", n -> n.path("salePrice").isNull() || n.path("salePrice").isMissingNode());
    // 할인 없음 사건
    discount("E-D1", 5, 2, 2500, 0, null);
    awaitItem("PRD-D1", n -> n.path("salePrice").isNull() || n.path("salePrice").isMissingNode());
    send(
        "ProductSaleStateChanged",
        "PRD-D1",
        2,
        Map.of(
            "productId",
            "PRD-D1",
            "sku",
            "E-D1",
            "state",
            "DISCONTINUED",
            "effectiveAt",
            "2026-11-19T06:00:00Z"));
  }

  void order(String type, String orderId, long version, Map<String, Object> payload) {
    kafka
        .send(
            "order.order.v1",
            orderId,
            EventEnvelopeFixtures.envelope(type)
                .from("order-service")
                .aggregate("Order", orderId, version)
                .payload(payload)
                .buildJson())
        .join();
  }

  /** 그 상품의 마지막 ProductRankingSignalsChanged 본문(outbox). 없으면 null. */
  tools.jackson.databind.JsonNode lastSignal(String productId) {
    var rows =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'ProductRankingSignalsChanged'"
                + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            productId);
    return rows.isEmpty() ? null : mapper.readTree(rows.get(0)).path("payload");
  }

  /**
   * 순위 신호(ADR-0072, 2026-11-19): 주문 확정 → 판매, 줄 수량 변경(출고 전 취소·반품, 주문 버전 큰 것만) → 순 판매, 리뷰 → 별점·베이지안
   * 점수. 바뀔 때마다 ProductRankingSignalsChanged(version +1), 같으면 다시 내지 않는다.
   */
  @Test
  void rankingSignalsFromOrdersAndReviews() throws Exception {
    card("PRD-K1", "[시험] 순위 상품", 1);
    product("PRD-K1", 1, "E-K1");
    published("PRD-K1", 1, "2026-08-01T00:00:00Z");
    for (int i = 0;
        i < 100
            && jdbc.queryForObject(
                    "SELECT count(*) FROM display_product WHERE product_id = 'PRD-K1' AND representative_sku = 'E-K1'",
                    Integer.class)
                == 0;
        i++) {
      Thread.sleep(200);
    }
    order(
        "OrderConfirmed",
        "ORD-K1",
        1,
        Map.of(
            "orderId",
            "ORD-K1",
            "orderVersion",
            1,
            "lines",
            List.of(
                Map.of("orderLineId", "ORD-K1-L1", "sku", "E-K1", "qty", 3),
                Map.of("orderLineId", "ORD-K1-L2", "sku", "E-OTHER", "qty", 9))));
    order(
        "OrderConfirmed",
        "ORD-K2",
        1,
        Map.of(
            "orderId",
            "ORD-K2",
            "orderVersion",
            1,
            "lines",
            List.of(Map.of("orderLineId", "ORD-K2-L1", "sku", "E-K1", "qty", 2))));
    // 출고 전 1개 취소(주문 버전 2) — 늦게 온 옛 버전(1)은 무시
    order(
        "OrderLineQuantityChanged",
        "ORD-K2",
        2,
        Map.of(
            "orderId",
            "ORD-K2",
            "orderLineId",
            "ORD-K2-L1",
            "sku",
            "E-K1",
            "originalQty",
            2,
            "unfulfilledQty",
            0,
            "handedOverQty",
            0,
            "cancelledBeforeShipQty",
            1,
            "returnedQty",
            0,
            "version",
            2,
            "changedAt",
            "2026-11-19T10:00:00Z"));
    order(
        "OrderLineQuantityChanged",
        "ORD-K2",
        1,
        Map.of(
            "orderId",
            "ORD-K2",
            "orderLineId",
            "ORD-K2-L1",
            "sku",
            "E-K1",
            "originalQty",
            2,
            "unfulfilledQty",
            0,
            "handedOverQty",
            0,
            "cancelledBeforeShipQty",
            0,
            "returnedQty",
            0,
            "version",
            1,
            "changedAt",
            "2026-11-19T09:00:00Z"));
    tools.jackson.databind.JsonNode sig = null;
    for (int i = 0; i < 100; i++) {
      sig = lastSignal("PRD-K1");
      if (sig != null && sig.path("salesWindow").asInt() == 4) {
        break;
      }
      Thread.sleep(300);
    }
    assertThat(sig).isNotNull();
    assertThat(sig.path("salesWindow").asInt()).as("3 + (2 − 1)").isEqualTo(4);

    // 반품 2개(주문 버전 2, returnedQty 있음) 뒤 인계 사건(버전 3, returnedQty 없음 — order 는 반품 사건에만 싣는다): 반품은
    // 그대로(리뷰 지적 3)
    Map<String, Object> ret = new java.util.LinkedHashMap<>();
    ret.putAll(Map.of("orderId", "ORD-K1", "orderLineId", "ORD-K1-L1", "sku", "E-K1"));
    ret.putAll(Map.of("originalQty", 3, "unfulfilledQty", 0, "handedOverQty", 3));
    ret.putAll(Map.of("cancelledBeforeShipQty", 0, "returnedQty", 2, "reason", "RETURN"));
    ret.putAll(Map.of("version", 2, "changedAt", "2026-11-19T11:00:00Z"));
    order("OrderLineQuantityChanged", "ORD-K1", 2, ret);
    Map<String, Object> handover = new java.util.LinkedHashMap<>(ret);
    handover.remove("returnedQty");
    handover.put("reason", "HANDOVER");
    handover.put("version", 3);
    handover.put("changedAt", "2026-11-19T12:00:00Z");
    order("OrderLineQuantityChanged", "ORD-K1", 3, handover);
    Map<String, Object> line = Map.of();
    for (int i = 0; i < 100; i++) {
      line =
          jdbc.queryForMap(
              "SELECT version, qty, returned_qty FROM sku_sales_line WHERE order_line_id = 'ORD-K1-L1'");
      if (((Number) line.get("version")).intValue() == 3) {
        break;
      }
      Thread.sleep(300);
    }
    assertThat(((Number) line.get("version")).intValue()).isEqualTo(3);
    assertThat(((Number) line.get("returned_qty")).intValue()).isEqualTo(2);
    assertThat(((Number) line.get("qty")).intValue()).as("3 − 0 − 2, 인계 사건에 반품이 없어도").isEqualTo(1);
    for (int i = 0; i < 100; i++) {
      sig = lastSignal("PRD-K1");
      if (sig != null && sig.path("salesWindow").asInt() == 2) {
        break;
      }
      Thread.sleep(300);
    }
    assertThat(sig.path("salesWindow").asInt()).as("1 + 1").isEqualTo(2);
    assertThat(sig.path("salesWindowDays").asInt()).isEqualTo(30);
    assertThat(sig.path("ratingCount").asInt()).isZero();
    assertThat(sig.path("ratingScore").isNull()).as("리뷰 없으면 점수 없음").isTrue();

    review("ReviewPublished", "RV-K1", "PRD-K1", 5, 1);
    review("ReviewPublished", "RV-K2", "PRD-K1", 4, 1);
    for (int i = 0; i < 100; i++) {
      sig = lastSignal("PRD-K1");
      if (sig.path("ratingCount").asInt() == 2) {
        break;
      }
      Thread.sleep(300);
    }
    assertThat(sig.path("ratingCount").asInt()).isEqualTo(2);
    assertThat(sig.path("ratingAverage").asDouble()).isEqualTo(4.5);
    // 베이지안 (5 × 4.0 + 9) ÷ (5 + 2) = 4.1429
    assertThat(sig.path("ratingScore").asDouble()).isEqualTo(4.1429);
    assertThat(sig.path("salesWindow").asInt()).isEqualTo(2);
    int version = sig.path("version").asInt();
    // 같은 값이면 다시 내지 않는다
    review("ReviewPublished", "RV-K2", "PRD-K1", 4, 1);
    Thread.sleep(1500);
    assertThat(lastSignal("PRD-K1").path("version").asInt()).isEqualTo(version);
    // 판매중지로 치워 다른 시험의 진열에 섞이지 않게
    send(
        "ProductSaleStateChanged",
        "PRD-K1",
        2,
        Map.of(
            "productId",
            "PRD-K1",
            "sku",
            "E-K1",
            "state",
            "DISCONTINUED",
            "effectiveAt",
            "2026-11-19T06:00:00Z"));
  }

  /**
   * BR-49(0.2.0, 2026-11-19): mfcId 를 주면 그 센터가 대표 SKU 를 취급하지 않는 상품은 뺀다 — 남은 것은 carried=true, 늦게 온 옛
   * version 은 무시, 모르는 센터는 빈 섹션(생략 안 함)·다음 커서 없음, mfcId 없으면 거르지 않고 carried=null.
   */
  @Test
  void centerHidesNotCarriedProducts() throws Exception {
    card("PRD-C1", "[시험] 취급 상품", 1);
    card("PRD-C2", "[시험] 미취급 상품", 1);
    product("PRD-C1", 1, "E-C1");
    published("PRD-C1", 1, "2026-10-01T00:01:00Z"); // 다른 시험(최근 게시 순)보다 뒤로
    product("PRD-C2", 1, "E-C2");
    published("PRD-C2", 1, "2026-10-01T00:02:00Z");
    handling("MFC-T", "E-C1", true, 1);
    handling("MFC-T", "E-C2", true, 1);
    handling("MFC-T", "E-C2", false, 2); // 꺼짐
    handling("MFC-T", "E-C2", true, 1); // 늦게 온 옛 사건 — 무시
    String path = "/storefront/sections/S-ALL/items?layoutVersion=0&size=50&mfcId=MFC-T";
    JsonNode page = get(path);
    for (int i = 0;
        i < 100 && !(ids(page.path("items")).contains("PRD-C1") && page.path("items").size() >= 1);
        i++) {
      Thread.sleep(300);
      page = get(path);
    }
    Thread.sleep(1500); // 꺼짐·옛 사건까지 반영
    page = get(path);
    assertThat(ids(page.path("items"))).contains("PRD-C1").doesNotContain("PRD-C2");
    page.path("items").forEach(i -> assertThat(i.path("carried").asBoolean()).isTrue());

    JsonNode unknown = get("/storefront/home?mfcId=MFC-NONE");
    JsonNode section = unknown.path("sections").path(0);
    assertThat(section.path("sectionId").asString()).as("섹션은 생략하지 않는다").isEqualTo("S-ALL");
    assertThat(section.path("items")).isEmpty();
    assertThat(section.path("nextCursor").isNull() || section.path("nextCursor").isMissingNode())
        .isTrue();

    JsonNode all = get("/storefront/sections/S-ALL/items?layoutVersion=0&size=50");
    assertThat(ids(all.path("items"))).contains("PRD-C1", "PRD-C2");
    all.path("items")
        .forEach(
            i ->
                assertThat(i.path("carried").isNull() || i.path("carried").isMissingNode())
                    .isTrue());
    // 같은 DB 를 쓰는 다른 시험(전체 진열 순서·쪽)에 섞이지 않게 판매중지로 치운다
    for (String id : List.of("PRD-C1", "PRD-C2")) {
      send(
          "ProductSaleStateChanged",
          id,
          2,
          Map.of(
              "productId",
              id,
              "sku",
              id.equals("PRD-C1") ? "E-C1" : "E-C2",
              "state",
              "DISCONTINUED",
              "effectiveAt",
              "2026-11-19T06:00:00Z"));
    }
    String allPath = "/storefront/sections/S-ALL/items?layoutVersion=0&size=50";
    for (int i = 0; i < 100 && ids(get(allPath).path("items")).contains("PRD-C1"); i++) {
      Thread.sleep(300);
    }
  }
}
