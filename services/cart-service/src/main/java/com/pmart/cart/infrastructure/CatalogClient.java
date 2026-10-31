package com.pmart.cart.infrastructure;

import com.pmart.cart.application.CartProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 장바구니가 쓰는 정본 조회. product 내부 API(SKU·구매 상한)·mfc-inventory 내부 가용은 서비스 토큰(공용 RestClient 가 {@code
 * /internal/} 에 붙인다), 콘텐츠 카드·가격은 공개 API. 조회 실패는 0·판매중지로 바꾸지 않고 예외(검증) 또는 불명(표시)으로 다룬다.
 */
@Component
public class CatalogClient {

  /** product SkuDetail 중 장바구니가 쓰는 것. */
  public record Sku(
      String sku, String productId, String baseProductId, int unitsPerSku, String saleState) {}

  public record Violation(String baseProductId, int totalUnits, int limitUnits) {}

  public record LimitResult(boolean passed, List<Violation> violations) {}

  public record Card(String name, @Nullable String imageUrl) {}

  public record Price(long amount, int version) {}

  public record Line(String sku, int quantity) {}

  /** mfc-inventory Availability 중 장바구니가 쓰는 것. available 은 A = E − R(산출 못 하면 null). */
  public record Stock(@Nullable Integer available, boolean handlingEnabled) {}

  private final RestClient product;
  private final RestClient content;
  private final RestClient pricing;
  private final RestClient inventory;
  private final ObjectMapper mapper;

  public CatalogClient(RestClient.Builder builder, CartProperties props, ObjectMapper mapper) {
    this.product = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.content = builder.clone().baseUrl(props.contentBaseUrl()).build();
    this.pricing = builder.clone().baseUrl(props.pricingBaseUrl()).build();
    this.inventory = builder.clone().baseUrl(props.inventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 담당 MFC 의 SKU 별 가용(표시용, 내부 API·서비스 토큰). 응답에 없는 SKU 는 빠진다. 실패는 예외 — 호출자가 불명으로 둔다. */
  public Map<String, Stock> stock(String mfcId, List<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    String raw =
        Downstream.call(
            "mfc-inventory-service availability",
            () ->
                inventory
                    .get()
                    .uri(
                        u -> {
                          u.path("/api/v1/internal/availability").queryParam("mfcId", mfcId);
                          skus.forEach(s -> u.queryParam("sku", s));
                          return u.build();
                        })
                    .retrieve()
                    .body(String.class));
    Map<String, Stock> out = new HashMap<>();
    for (JsonNode a : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
      JsonNode available = a.path("available");
      out.put(
          a.path("sku").asString(),
          new Stock(
              available.isNumber() ? available.asInt() : null,
              a.path("handlingEnabled").asBoolean(false)));
    }
    return out;
  }

  /** 없는 SKU 면 empty. */
  public Optional<Sku> sku(String sku) {
    try {
      String raw =
          Downstream.call(
              "product-service skus",
              () ->
                  product
                      .get()
                      .uri("/api/v1/internal/skus/{sku}", sku)
                      .retrieve()
                      .body(String.class));
      return Optional.of(sku(mapper.readTree(raw == null ? "{}" : raw)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  /** 여러 SKU(최대 100). 없는 SKU 는 빠진다. */
  public Map<String, Sku> skus(List<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    String raw =
        Downstream.call(
            "product-service skus",
            () ->
                product
                    .get()
                    .uri("/api/v1/internal/skus?skus={s}", String.join(",", skus))
                    .retrieve()
                    .body(String.class));
    Map<String, Sku> out = new HashMap<>();
    for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
      Sku s = sku(n);
      out.put(s.sku(), s);
    }
    return out;
  }

  /** 기초상품별 Σ 수량×구성 낱개 수 ≤ 상한(product 정책값). 정책값이 없으면 product 의 CONFIG_REQUIRED 를 그대로. */
  public LimitResult purchaseLimit(String accountRef, List<Line> lines) {
    List<Map<String, Object>> items = new ArrayList<>();
    lines.forEach(l -> items.add(Map.of("sku", l.sku(), "quantity", l.quantity())));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accountId", accountRef);
    body.put("items", items);
    String raw;
    try {
      raw =
          Downstream.call(
              "product-service purchase-limit-checks",
              () ->
                  product
                      .post()
                      .uri("/api/v1/internal/purchase-limit-checks")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.UnprocessableContent e) {
      throw PmartException.configRequired("product.purchase-limit-units");
    }
    JsonNode r = mapper.readTree(raw == null ? "{}" : raw);
    List<Violation> violations = new ArrayList<>();
    for (JsonNode v : r.path("violations")) {
      violations.add(
          new Violation(
              v.path("baseProductId").asString(),
              v.path("totalUnits").asInt(),
              v.path("limitUnits").asInt()));
    }
    return new LimitResult(r.path("passed").asBoolean(false), violations);
  }

  /** 게시된 콘텐츠 카드(표시용). 실패는 예외 — 호출자가 표시를 불명으로 둔다. */
  public Map<String, Card> cards(List<String> productIds) {
    if (productIds.isEmpty()) {
      return Map.of();
    }
    String raw =
        Downstream.call(
            "product-content-service cards",
            () ->
                content
                    .get()
                    .uri("/api/v1/product-contents?productIds={ids}", String.join(",", productIds))
                    .retrieve()
                    .body(String.class));
    Map<String, Card> out = new HashMap<>();
    for (JsonNode c : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
      JsonNode img = c.path("mainImage").path("url");
      out.put(
          c.path("productId").asString(),
          new Card(c.path("name").asString(), img.isString() ? img.asString() : null));
    }
    return out;
  }

  /** 표시 가격. 가격 없는 SKU 는 빠진다(0원 아님). */
  public Map<String, Price> prices(List<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    String raw =
        Downstream.call(
            "pricing-service prices",
            () ->
                pricing
                    .get()
                    .uri("/api/v1/prices?skus={s}", String.join(",", skus))
                    .retrieve()
                    .body(String.class));
    Map<String, Price> out = new HashMap<>();
    for (JsonNode p : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
      out.put(
          p.path("sku").asString(),
          new Price(p.path("basePrice").asLong(), p.path("priceVersion").asInt()));
    }
    return out;
  }

  private static Sku sku(JsonNode n) {
    return new Sku(
        n.path("sku").asString(),
        n.path("productId").asString(),
        n.path("baseProductId").asString(),
        n.path("unitsPerSku").asInt(1),
        n.path("saleState").asString("UNKNOWN"));
  }
}
