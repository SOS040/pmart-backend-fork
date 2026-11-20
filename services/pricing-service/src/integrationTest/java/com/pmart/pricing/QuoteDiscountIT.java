package com.pmart.pricing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 견적 할인(ADR-0082 2단계): 기본가 → 기간 할인(promotion 이 고른 개당 할인 × 수량) → 장바구니 쿠폰(기간 할인 뒤 금액에 비례 안분, 남은 원은 큰
 * 품목). 쿠폰을 못 쓰면 거절 없이 applicable=false. promotion 장애면 503(정가로 계산하지 않음). promotion 은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "promotion",
        baseUrlProperties = {"pmart.pricing.promotion-base-url"}))
class QuoteDiscountIT extends PostgresKafkaContainerSupport {

  static final String ORDER = TestJwt.service("order-service");
  static final String A = "E-71000001";
  static final String B = "E-71000002";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("promotion")
  WireMockServer promotion;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    price(A, 10_000);
    price(B, 3_000);
    promotion.resetAll();
  }

  void price(String sku, long price) {
    jdbc.update(
        "INSERT INTO price_version (sku, version, base_price, effective_from, state, approval_id,"
            + " created_at) VALUES (?, 1, ?, ?, 'EFFECTIVE', 'AP-seed', now()) ON CONFLICT DO NOTHING",
        sku,
        price,
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
  }

  Res quote(Map<String, Object> body) {
    return http.post()
        .uri("/internal/quotes")
        .headers(
            h -> {
              h.setBearerAuth(ORDER);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "order-service");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange(
            (q, r) ->
                new Res(r.getStatusCode().value(), mapper.readTree(r.getBody().readAllBytes())));
  }

  void stubDiscounts(String json) {
    promotion.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/price-discounts"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  static Map<String, Object> body(String coupon) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("accountId", "CU-1");
    m.put(
        "items",
        List.of(
            Map.of("lineRef", "l-a", "sku", A, "quantity", 2),
            Map.of("lineRef", "l-b", "sku", B, "quantity", 1)));
    if (coupon != null) {
      m.put("couponIds", List.of(coupon));
    }
    return m;
  }

  @Test
  void periodDiscountThenCouponAllocatedByAmount() {
    // A 개당 1,000원 할인, B 없음
    stubDiscounts(
        """
        {"asOf":"2026-11-21T00:00:00Z","items":[
          {"sku":"%s","promotionId":"PRM-A","name":"A 10%%","unitPrice":10000,"unitDiscount":1000,
           "discountedUnitPrice":9000,"ratePercent":10,"endsAt":"2026-12-01T00:00:00Z"}]}
        """
            .formatted(A));
    // 기간 할인 뒤 21,000원에서 쿠폰 1,000원
    promotion.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/coupon-quotes"))
            .withRequestBody(
                equalToJson("{\"couponId\":\"CPN-1\",\"itemAmount\":21000}", true, true))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"couponId":"CPN-1","applicable":true,"discountAmount":1000,"promotionId":"PRM-C","name":"천원 쿠폰"}
                        """)));
    Res r = quote(body("CPN-1"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode q = r.body();
    assertThat(q.path("baseItemAmount").asInt()).isEqualTo(23_000);
    assertThat(q.path("promotionDiscount").asInt()).isEqualTo(2_000);
    assertThat(q.path("itemAmount").asInt()).as("기간 할인 뒤·쿠폰 전").isEqualTo(21_000);
    assertThat(q.path("couponDiscount").asInt()).isEqualTo(1_000);
    assertThat(q.path("discountTotal").asInt()).isEqualTo(3_000);
    assertThat(q.path("coupon").path("applicable").asBoolean()).isTrue();
    assertThat(q.path("appliedStages").toString()).contains("PROMOTION", "CART_COUPON");
    // 쿠폰 안분: A 18,000 / B 3,000 → 857 / 142(+남은 1원은 큰 A) = 858 / 142
    JsonNode la = q.path("lines").path(0);
    JsonNode lb = q.path("lines").path(1);
    assertThat(la.path("promotionDiscount").asInt()).isEqualTo(2_000);
    assertThat(la.path("couponShare").asInt()).isEqualTo(858);
    assertThat(lb.path("couponShare").asInt()).isEqualTo(142);
    assertThat(la.path("discountShare").asInt()).isEqualTo(2_858);
    assertThat(la.path("netAmount").asInt()).isEqualTo(20_000 - 2_858);
    assertThat(la.path("promotionId").asString()).isEqualTo("PRM-A");
    assertThat(lb.path("netAmount").asInt()).isEqualTo(3_000 - 142);
  }

  @Test
  void inapplicableCouponIsReportedNotRejected() {
    stubDiscounts("{\"asOf\":\"2026-11-21T00:00:00Z\",\"items\":[]}");
    promotion.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/coupon-quotes"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"couponId\":\"CPN-2\",\"applicable\":false,\"reason\":\"MIN_ORDER_NOT_MET\",\"discountAmount\":0}")));
    Res r = quote(body("CPN-2"));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("itemAmount").asInt()).isEqualTo(23_000);
    assertThat(r.body().path("couponDiscount").asInt()).isZero();
    assertThat(r.body().path("coupon").path("reason").asString()).isEqualTo("MIN_ORDER_NOT_MET");
    assertThat(r.body().path("appliedStages").toString()).doesNotContain("CART_COUPON");
  }

  @Test
  void promotionDownIsUnavailableNotFullPrice() {
    promotion.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/price-discounts"))
            .willReturn(aResponse().withStatus(503)));
    Res r = quote(body(null));
    assertThat(r.status()).isEqualTo(503);
  }

  Res get(String path) {
    return http.get()
        .uri(path)
        .exchange(
            (q, r) ->
                new Res(r.getStatusCode().value(), mapper.readTree(r.getBody().readAllBytes())));
  }

  @Test
  void displayPricesCarryTheCurrentDiscountAndFallBackToBasePrice() {
    stubDiscounts(
        """
        {"asOf":"2026-11-21T00:00:00Z","items":[
          {"sku":"%s","promotionId":"PRM-A","name":"A 10%%","unitPrice":10000,"unitDiscount":1000,
           "discountedUnitPrice":9000,"ratePercent":10,"endsAt":"2026-12-01T00:00:00Z"}]}
        """
            .formatted(A));
    // 비회원도 본다(토큰 없음)
    Res list = get("/prices?skus=" + A + "," + B);
    assertThat(list.status()).as(list.body().toString()).isEqualTo(200);
    JsonNode a = list.body().path("items").path(0);
    JsonNode b = list.body().path("items").path(1);
    assertThat(a.path("sku").asString()).isEqualTo(A);
    assertThat(a.path("discountAvailable").asBoolean()).isTrue();
    assertThat(a.path("discount").path("salePrice").asInt()).isEqualTo(9_000);
    assertThat(a.path("discount").path("unitDiscount").asInt()).isEqualTo(1_000);
    assertThat(a.path("discount").path("ratePercent").asInt()).isEqualTo(10);
    assertThat(a.path("discount").path("name").asString()).isEqualTo("A 10%");
    assertThat(b.path("discount").isMissingNode() || b.path("discount").isNull()).isTrue();
    Res one = get("/prices/" + A);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.body().path("discount").path("salePrice").asInt()).isEqualTo(9_000);
    // 내부 조회에는 할인을 붙이지 않는다
    assertThat(
            promotion.findAll(
                WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/price-discounts"))))
        .hasSize(2);

    // promotion 장애 → 정가만, discountAvailable=false(거절하지 않는다)
    promotion.resetAll();
    promotion.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/price-discounts"))
            .willReturn(aResponse().withStatus(503)));
    Res down = get("/prices?skus=" + A);
    assertThat(down.status()).isEqualTo(200);
    assertThat(down.body().path("items").path(0).path("basePrice").asInt()).isEqualTo(10_000);
    assertThat(down.body().path("items").path(0).path("discountAvailable").asBoolean()).isFalse();
    assertThat(
            down.body().path("items").path(0).path("discount").isMissingNode()
                || down.body().path("items").path(0).path("discount").isNull())
        .isTrue();
  }
}
