package com.pmart.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구매 기록 활용(2026-11-26, 계약 0.24.0): 같은 "산 것" 정의(CANCELLED·EXPIRED 아님, 출고 전 전부 취소된 줄 빼기, 서로 다른 주문 수)로
 * 고객 본인 자주 산 상품(①)과 직원 고객 구매 지표(O-13 — 첫 구매 기준, 기간 끝까지).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=purchase-test"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "content",
        baseUrlProperties = {"pmart.order.content-base-url"}))
class PurchaseHistoryIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderStore store;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("content")
  WireMockServer content;

  RestClient http;

  static final String ADMIN = TestJwt.operator("ad-ph", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("cs-ph", List.of("CS_AGENT"), List.of());

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    content.resetAll();
  }

  /**
   * ①: 서로 다른 주문 수 ≥ minOrders, 기간 안(windowDays), 취소·만료 주문·숨긴 주문·전부 출고 전 취소 줄은 세지 않음, 이름·옵션은 가장 최근
   * 사본, 횟수 → 마지막 구매 순, 사진은 product-content. 고객만, 글자 그대로 경로라 주문 상세와 겹치지 않음.
   */
  @Test
  void frequentItemsCountDistinctPurchasedOrders() {
    String account = "ACC-FQ-" + UUID.randomUUID();
    String mfc = "MFC-FQ";
    String x = "FX-" + UUID.randomUUID().toString().substring(0, 8);
    String y = "FY-" + UUID.randomUUID().toString().substring(0, 8);
    String z = "FZ-" + UUID.randomUUID().toString().substring(0, 8);
    Instant now = Instant.now();
    order(
        account,
        mfc,
        "COMPLETED",
        now.minus(10, ChronoUnit.DAYS),
        new L(x, "P-" + x, 2, 4_000, 0, "옛 이름", null),
        new L(y, "P-" + y, 1, 2_000, 0, "와이", null));
    order(
        account,
        mfc,
        "CONFIRMED",
        now.minus(5, ChronoUnit.DAYS),
        new L(x, "P-" + x, 1, 2_000, 0, "새 이름", "1kg"),
        new L(z, "P-" + z, 1, 1_000, 1, "지", null)); // Z 전부 출고 전 취소
    order(
        account,
        mfc,
        "CANCELLED",
        now.minus(3, ChronoUnit.DAYS),
        new L(y, "P-" + y, 1, 2_000, 0, "와이", null));
    order(
        account,
        mfc,
        "EXPIRED",
        now.minus(3, ChronoUnit.DAYS),
        new L(y, "P-" + y, 1, 2_000, 0, "와이", null));
    order(
        account,
        mfc,
        "COMPLETED",
        now.minus(200, ChronoUnit.DAYS),
        new L(y, "P-" + y, 3, 6_000, 0, "와이", null)); // 180일 밖
    String hidden =
        order(
            account,
            mfc,
            "COMPLETED",
            now.minus(2, ChronoUnit.DAYS),
            new L(y, "P-" + y, 1, 2_000, 0, "와이", null));
    jdbc.update("UPDATE customer_order SET hidden_at = now() WHERE order_id = ?", hidden);
    // 남의 주문은 세지 않는다
    order(
        "ACC-FQ-OTHER-" + UUID.randomUUID(),
        mfc,
        "COMPLETED",
        now.minus(1, ChronoUnit.DAYS),
        new L(y, "P-" + y, 1, 2_000, 0, "와이", null));
    content.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/product-contents"))
            .willReturn(
                WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"productId\":\"P-"
                            + x
                            + "\",\"mainImage\":{\"url\":\"/api/v1/content-images/IMG-X\"}}]}")));
    String token = TestJwt.customer(account);

    JsonNode def = ok(token, "/orders/frequent-items");
    assertThat(def.path("items")).hasSize(1);
    JsonNode ix = def.path("items").path(0);
    assertThat(ix.path("sku").asString()).isEqualTo(x);
    assertThat(ix.path("productId").asString()).isEqualTo("P-" + x);
    assertThat(ix.path("orderCount").asInt()).isEqualTo(2);
    assertThat(ix.path("totalQuantity").asInt()).isEqualTo(3);
    assertThat(ix.path("productName").asString()).isEqualTo("새 이름");
    assertThat(ix.path("optionLabel").asString()).isEqualTo("1kg");
    assertThat(ix.path("imageUrl").asString()).isEqualTo("/api/v1/content-images/IMG-X");
    assertThat(Instant.parse(ix.path("lastPurchasedAt").asString()))
        .isCloseTo(
            now.minus(5, ChronoUnit.DAYS),
            org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    assertThat(Instant.parse(def.path("from").asString()))
        .isBetween(now.minus(181, ChronoUnit.DAYS), now.minus(179, ChronoUnit.DAYS));

    // 1번 이상: Y 는 첫 주문 하나만 산 것(취소·만료·숨김·남의 것 안 셈), Z 는 전부 취소라 없음
    JsonNode once = ok(token, "/orders/frequent-items?minOrders=1").path("items");
    assertThat(once.findValuesAsString("sku")).containsExactly(x, y);
    assertThat(once.path(1).path("orderCount").asInt()).isEqualTo(1);
    assertThat(once.path(1).path("imageUrl").isNull()).isTrue();
    // 365일: Y 가 200일 전 주문까지 2번 — 횟수 같으면 마지막 구매가 최근인 X 먼저
    JsonNode year = ok(token, "/orders/frequent-items?windowDays=365").path("items");
    assertThat(year.findValuesAsString("sku")).containsExactly(x, y);
    assertThat(year.path(1).path("totalQuantity").asInt()).isEqualTo(4);
    assertThat(ok(token, "/orders/frequent-items?minOrders=1&limit=1").path("items")).hasSize(1);

    assertThat(status(token, "/orders/frequent-items?windowDays=366")).isEqualTo(400);
    assertThat(status(token, "/orders/frequent-items?minOrders=11")).isEqualTo(400);
    assertThat(status(token, "/orders/frequent-items?limit=51")).isEqualTo(400);
    assertThat(status(ADMIN, "/orders/frequent-items")).isEqualTo(403);
    // 산 것이 없는 고객 — 빈 목록
    assertThat(
            ok(TestJwt.customer("ACC-FQ-NONE-" + UUID.randomUUID()), "/orders/frequent-items")
                .path("items"))
        .isEmpty();
  }

  /**
   * O-13: 첫 산 주문의 확정 시각(한국 날짜)이 기간 안인 고객만, 기간 끝까지 센다. 취소 주문·전부 취소된 주문은 산 것이 아님, mfcId 면 그 센터 주문만.
   * 권한은 다른 주문 집계와 같고 기간 366일.
   */
  @Test
  void customerMetricsUseFirstPurchaseCohort() {
    String m1 = "MFC-CM-" + UUID.randomUUID().toString().substring(0, 8);
    String m2 = "MFC-CM-" + UUID.randomUUID().toString().substring(0, 8);
    String c1 = "ACC-CM1-" + UUID.randomUUID();
    String c2 = "ACC-CM2-" + UUID.randomUUID();
    String c3 = "ACC-CM3-" + UUID.randomUUID();
    String c4 = "ACC-CM4-" + UUID.randomUUID();
    String c5 = "ACC-CM5-" + UUID.randomUUID();
    String c6 = "ACC-CM6-" + UUID.randomUUID();
    // c1: 2017-03-01 00:30 KST 첫 구매 + 03-10(2개 중 1개 출고 전 취소 → 10,000) = 재구매
    order(c1, m1, "COMPLETED", Instant.parse("2017-02-28T15:30:00Z"), line(10_000, 1, 0));
    order(c1, m1, "COMPLETED", Instant.parse("2017-03-10T03:00:00Z"), line(20_000, 2, 1));
    // c2: 03-05 한 번, 04-05 는 기간 끝 뒤라 안 셈 = 한 번만
    order(c2, m1, "COMPLETED", Instant.parse("2017-03-05T03:00:00Z"), line(5_000, 1, 0));
    order(c2, m1, "COMPLETED", Instant.parse("2017-04-05T03:00:00Z"), line(7_000, 1, 0));
    // c3: 첫 구매 2017-02-28 23:30 KST(기간 전) — 빠짐
    order(c3, m1, "COMPLETED", Instant.parse("2017-02-28T14:30:00Z"), line(1_000, 1, 0));
    order(c3, m1, "COMPLETED", Instant.parse("2017-03-15T03:00:00Z"), line(1_000, 1, 0));
    // c4: 03-02 취소 주문은 산 것 아님 → 첫 구매 03-20 = 한 번만
    order(c4, m1, "CANCELLED", Instant.parse("2017-03-02T03:00:00Z"), line(4_000, 1, 0));
    order(c4, m1, "COMPLETED", Instant.parse("2017-03-20T03:00:00Z"), line(3_000, 1, 0));
    // c5: 다른 센터만
    order(c5, m2, "COMPLETED", Instant.parse("2017-03-03T03:00:00Z"), line(2_000, 1, 0));
    // c6: 줄이 전부 출고 전 취소된 주문뿐 — 산 것 없음
    order(c6, m1, "CONFIRMED", Instant.parse("2017-03-06T03:00:00Z"), line(9_000, 2, 2));

    String march = "from=2017-03-01&to=2017-03-31";
    JsonNode one = ok(ADMIN, "/orders/metrics/customers?" + march + "&mfcId=" + m1);
    assertThat(one.path("mfcId").asString()).isEqualTo(m1);
    assertThat(one.path("customerCount").asInt()).isEqualTo(3);
    assertThat(one.path("repeatCustomerCount").asInt()).isEqualTo(1);
    assertThat(one.path("oneTimeCustomerCount").asInt()).isEqualTo(2);
    assertThat(one.path("orderCount").asInt()).isEqualTo(4);
    assertThat(one.path("purchaseAmount").asLong()).isEqualTo(28_000);
    // 모든 센터 — c5 가 더해진다(2017 년은 이 시험의 주문뿐)
    JsonNode all = ok(ADMIN, "/orders/metrics/customers?" + march);
    assertThat(all.path("customerCount").asInt()).isEqualTo(4);
    assertThat(all.path("oneTimeCustomerCount").asInt()).isEqualTo(3);
    assertThat(all.path("orderCount").asInt()).isEqualTo(5);
    assertThat(all.path("purchaseAmount").asLong()).isEqualTo(30_000);
    // 기간을 4월까지 늘리면 c2 의 04-05 가 더해져 재구매가 된다
    JsonNode longer =
        ok(ADMIN, "/orders/metrics/customers?from=2017-03-01&to=2017-04-30&mfcId=" + m1);
    assertThat(longer.path("repeatCustomerCount").asInt()).isEqualTo(2);
    assertThat(longer.path("purchaseAmount").asLong()).isEqualTo(35_000);

    String sup = TestJwt.operator("sv-ph", List.of("MFC_SUPERVISOR"), List.of(m1));
    assertThat(status(sup, "/orders/metrics/customers?" + march + "&mfcId=" + m1)).isEqualTo(200);
    assertThat(status(sup, "/orders/metrics/customers?" + march)).isEqualTo(400);
    assertThat(status(sup, "/orders/metrics/customers?" + march + "&mfcId=" + m2)).isEqualTo(403);
    assertThat(status(CS, "/orders/metrics/customers?" + march)).isEqualTo(403);
    assertThat(status(TestJwt.customer(c1), "/orders/metrics/customers?" + march)).isEqualTo(403);
    assertThat(status(ADMIN, "/orders/metrics/customers?from=2017-01-01&to=2018-01-01"))
        .isEqualTo(200); // 366일
    assertThat(status(ADMIN, "/orders/metrics/customers?from=2017-01-01&to=2018-01-02"))
        .isEqualTo(400); // 367일
    assertThat(status(ADMIN, "/orders/metrics/customers?from=2017-03-02&to=2017-03-01"))
        .isEqualTo(400);
  }

  // ---------- 도우미 ----------

  ResponseEntity<String> call(String token, String path) {
    return http.get()
        .uri(path)
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(String.class);
  }

  JsonNode ok(String token, String path) {
    var e = call(token, path);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    return mapper.readTree(e.getBody());
  }

  int status(String token, String path) {
    return call(token, path).getStatusCode().value();
  }

  /** 주문행: SKU·상품·수량·줄 금액·출고 전 취소 수량·이름·옵션 사본. */
  record L(
      String sku,
      String productId,
      int qty,
      long lineAmount,
      int cancelled,
      @Nullable String name,
      @Nullable String option) {}

  static L line(long amount, int qty, int cancelled) {
    return new L("CM-SKU", "P-CM", qty, amount, cancelled, "고객 지표", null);
  }

  String order(String account, String mfc, String status, Instant confirmedAt, L... ls) {
    String orderId = "ORD-PH-" + UUID.randomUUID();
    long total = 0;
    for (L l : ls) {
      total += l.lineAmount();
    }
    var o =
        new OrderRow(
            orderId,
            account,
            "DRF-" + orderId,
            mfc,
            status,
            1,
            "SINGLE",
            null,
            null,
            null,
            "CANCEL_OOS_ONLY",
            null,
            "ADDR-1",
            1,
            1,
            total,
            0,
            0,
            "STANDARD",
            0,
            total,
            false,
            1_000,
            1,
            "PA-" + orderId,
            "PG-" + orderId,
            total,
            "RSV-" + orderId,
            null,
            null,
            confirmedAt,
            null);
    List<LineRow> lines = new ArrayList<>();
    int n = 1;
    for (L l : ls) {
      lines.add(
          new LineRow(
              orderId + "-L" + n,
              orderId,
              n,
              null,
              l.sku(),
              l.productId(),
              1,
              l.qty(),
              l.lineAmount() / l.qty(),
              l.lineAmount(),
              0,
              100,
              l.qty(),
              l.qty() - l.cancelled(),
              0,
              l.cancelled(),
              0,
              0,
              l.name(),
              l.option(),
              0));
      n++;
    }
    store.insert(o, lines);
    return orderId;
  }
}
