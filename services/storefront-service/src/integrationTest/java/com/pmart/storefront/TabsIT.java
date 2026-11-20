package com.pmart.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 랭킹·신상품 탭(2026-11-20 ADR-0075, 사용자 "탭 + 배너·테마"): 탭 정책값(신규 14일·랭킹 3개)을 시험 속성으로 켠다. 진열 사본·순위 신호 표에
 * 직접 넣고, 이 시험만의 센터(MFC-TABS) 취급으로 다른 시험 상품과 섞이지 않게 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.storefront.content-base-url=http://localhost:1",
      "spring.cloud.function.definition=catalogEvents;handlingEvents;reviewEvents;orderEvents;approvalEvents;promotionEvents",
      "spring.cloud.stream.bindings.orderEvents-in-0.destination=order.order.v1",
      "pmart.storefront.ranking.sales-window-days=30",
      "pmart.storefront.ranking.rating-prior-count=20",
      "pmart.storefront.ranking.rating-prior-mean=4.0",
      "pmart.storefront.tabs.new-window-days=14",
      "pmart.storefront.tabs.ranking-size=3",
      "pmart.it.context=TabsIT"
    })
class TabsIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-TABS";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    Instant now = Instant.now();
    // 상품 5개: T1·T2 는 3일 전 게시(신상품), T3·T4·T5 는 40일 전. 판매: T3 50 > T1 20 = T4 20(별점 점수로 T4 위) > T5 5,
    // T2 0(랭킹 밖)
    product("PRD-T1", now.minus(Duration.ofDays(3)));
    product("PRD-T2", now.minus(Duration.ofDays(2)));
    product("PRD-T3", now.minus(Duration.ofDays(40)));
    product("PRD-T4", now.minus(Duration.ofDays(41)));
    product("PRD-T5", now.minus(Duration.ofDays(42)));
    signal("PRD-T1", 20, 4.0);
    signal("PRD-T2", 0, null);
    signal("PRD-T3", 50, 4.5);
    signal("PRD-T4", 20, 4.6);
    signal("PRD-T5", 5, 3.0);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM product_ranking_signal WHERE product_id LIKE 'PRD-T_'");
    jdbc.update("DELETE FROM display_product WHERE product_id LIKE 'PRD-T_'");
    jdbc.update("DELETE FROM sku_handling WHERE mfc_id = ?", MFC);
  }

  void product(String id, Instant publishedAt) {
    jdbc.update(
        "INSERT INTO display_product (product_id, product_version, representative_sku, name, content_version,"
            + " content_published_at, updated_at) VALUES (?, 1, ?, ?, 1, ?, now())",
        id,
        "E-" + id,
        "[시험] " + id,
        Timestamp.from(publishedAt));
    jdbc.update(
        "INSERT INTO sku_handling (mfc_id, sku, handling_enabled, version, changed_at) VALUES (?, ?, true, 1, now())",
        MFC,
        "E-" + id);
  }

  void signal(String id, int sales, Double score) {
    jdbc.update(
        "INSERT INTO product_ranking_signal (product_id, rating_count, rating_average, rating_score, sales_window,"
            + " window_days, version, published_at) VALUES (?, 0, NULL, ?, ?, 30, 1, now())",
        id,
        score,
        sales);
  }

  JsonNode get(String path) {
    String body = http.get().uri(path).retrieve().body(String.class);
    return mapper.readTree(body == null ? "{}" : body);
  }

  static List<String> ids(JsonNode items) {
    List<String> out = new ArrayList<>();
    items.forEach(i -> out.add(i.path("productId").asString()));
    return out;
  }

  @Test
  void rankingAndNewTabs() {
    JsonNode home = get("/storefront/home?mfcId=" + MFC);
    assertThat(home.path("tabs").toString()).isEqualTo("[\"HOME\",\"RANKING\",\"NEW\"]");
    // 홈 영역에서도 신규 기간 안이면 NEW 배지
    JsonNode homeItems = home.path("sections").path(0).path("items");
    assertThat(ids(homeItems)).startsWith("PRD-T2", "PRD-T1");
    assertThat(homeItems.path(0).path("badge").asString()).isEqualTo("NEW");
    assertThat(homeItems.path(2).path("badge").asString()).isEqualTo("NONE");

    // 랭킹: 판매 많은 순, 같으면 별점 점수, 판매 0 은 빠짐, 3개까지
    JsonNode ranking = get("/storefront/tabs/RANKING?mfcId=" + MFC);
    JsonNode section = ranking.path("sections").path(0);
    assertThat(section.path("kind").asString()).isEqualTo("RANKING");
    assertThat(section.path("subtitle").asString()).contains("30일");
    assertThat(ids(section.path("items"))).containsExactly("PRD-T3", "PRD-T4", "PRD-T1");
    assertThat(section.path("items").path(0).path("badge").asString()).isEqualTo("RANK");
    assertThat(section.path("items").path(2).path("rank").asInt()).isEqualTo(3);
    assertThat(section.path("nextCursor").isNull() || section.path("nextCursor").isMissingNode())
        .as("랭킹 크기 3 — 다음 쪽 없음")
        .isTrue();
    // 홈 ?tab= 도 같다
    assertThat(
            ids(
                get("/storefront/home?tab=RANKING&mfcId=" + MFC)
                    .path("sections")
                    .path(0)
                    .path("items")))
        .containsExactly("PRD-T3", "PRD-T4", "PRD-T1");
    // 영역 다음 묶음: 2개씩 → 2위까지, 다음 쪽 3위, 그 너머 없음
    JsonNode p1 = get("/storefront/sections/S-RANKING/items?size=2&mfcId=" + MFC);
    assertThat(ids(p1.path("items"))).containsExactly("PRD-T3", "PRD-T4");
    JsonNode p2 =
        get(
            "/storefront/sections/S-RANKING/items?size=2&mfcId="
                + MFC
                + "&cursor="
                + p1.path("page").path("nextCursor").asString());
    assertThat(ids(p2.path("items"))).containsExactly("PRD-T1");
    assertThat(p2.path("items").path(0).path("rank").asInt()).isEqualTo(3);
    assertThat(p2.path("page").path("nextCursor").isNull()).isTrue();

    // 신상품: 14일 안 처음 게시, 최신 순, NEW
    JsonNode fresh = get("/storefront/tabs/NEW?mfcId=" + MFC).path("sections").path(0);
    assertThat(fresh.path("kind").asString()).isEqualTo("NEW");
    assertThat(ids(fresh.path("items"))).containsExactly("PRD-T2", "PRD-T1");
    assertThat(fresh.path("items").path(1).path("badge").asString()).isEqualTo("NEW");
  }
}
