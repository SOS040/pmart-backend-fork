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
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
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
      product("PRD-H" + i, 1, "E-H" + i);
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
    assertThat(status("/storefront/tabs/RANKING")).isEqualTo(404);
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
