package com.pmart.pricing.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.pricing.application.PricingProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * promotion-service 내부 조회(ADR-0082): SKU 기간 할인({@code POST /internal/price-discounts}), 쿠폰 할인
 * 계산({@code POST /internal/coupon-quotes}). 둘 다 상태를 바꾸지 않는 조회. 실패는 {@link Downstream} 규칙(미전달·503 →
 * DEPENDENCY_UNAVAILABLE) — 할인 없이 정가로 계산하지 않는다. 주소가 비면 쓰지 않는다({@link #enabled()}).
 */
@Component
public class PromotionClient {

  public record Discount(String sku, String promotionId, long unitDiscount) {}

  /** 화면 표시용(ADR-0082 4단계) — 행사 이름·할인율·끝 시각까지. */
  public record DisplayDiscount(
      String sku,
      String promotionId,
      String name,
      long unitDiscount,
      int ratePercent,
      Instant endsAt) {}

  public record CouponResult(
      String couponId,
      boolean applicable,
      @Nullable String reason,
      long discountAmount,
      @Nullable String promotionId,
      @Nullable String name) {}

  private final @Nullable RestClient promotion;
  private final ObjectMapper mapper;

  public PromotionClient(RestClient.Builder builder, PricingProperties props, ObjectMapper mapper) {
    String url = props.promotionBaseUrl();
    this.promotion = url == null || url.isBlank() ? null : builder.clone().baseUrl(url).build();
    this.mapper = mapper;
  }

  public boolean enabled() {
    return promotion != null;
  }

  /** sku → 단가. 걸린 할인이 있는 SKU 만 돌려준다. */
  public Map<String, Discount> discounts(Map<String, Long> unitPrices, Instant at) {
    Map<String, Discount> out = new HashMap<>();
    if (promotion == null || unitPrices.isEmpty()) {
      return out;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    unitPrices.forEach((sku, price) -> items.add(Map.of("sku", sku, "unitPrice", price)));
    JsonNode body =
        post("/api/v1/internal/price-discounts", Map.of("at", at.toString(), "items", items));
    for (JsonNode i : body.path("items")) {
      out.put(
          i.path("sku").asString(),
          new Discount(
              i.path("sku").asString(),
              i.path("promotionId").asString(),
              i.path("unitDiscount").asLong()));
    }
    return out;
  }

  /** 표시용 할인. 걸린 할인이 있는 SKU 만. 꺼져 있으면 빈 결과, 실패는 예외(Downstream). */
  public Map<String, DisplayDiscount> displayDiscounts(Map<String, Long> unitPrices, Instant at) {
    Map<String, DisplayDiscount> out = new HashMap<>();
    if (promotion == null || unitPrices.isEmpty()) {
      return out;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    unitPrices.forEach((sku, price) -> items.add(Map.of("sku", sku, "unitPrice", price)));
    JsonNode body =
        post("/api/v1/internal/price-discounts", Map.of("at", at.toString(), "items", items));
    for (JsonNode i : body.path("items")) {
      out.put(
          i.path("sku").asString(),
          new DisplayDiscount(
              i.path("sku").asString(),
              i.path("promotionId").asString(),
              i.path("name").asString(""),
              i.path("unitDiscount").asLong(),
              i.path("ratePercent").asInt(0),
              Instant.parse(i.path("endsAt").asString())));
    }
    return out;
  }

  public CouponResult coupon(String couponId, String accountId, long itemAmount, Instant at) {
    if (promotion == null) {
      return new CouponResult(couponId, false, "UNAVAILABLE", 0, null, null);
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("couponId", couponId);
    req.put("accountId", accountId);
    req.put("itemAmount", itemAmount);
    req.put("at", at.toString());
    JsonNode b = post("/api/v1/internal/coupon-quotes", req);
    return new CouponResult(
        couponId,
        b.path("applicable").asBoolean(false),
        b.hasNonNull("reason") ? b.get("reason").asString() : null,
        b.path("discountAmount").asLong(0),
        b.hasNonNull("promotionId") ? b.get("promotionId").asString() : null,
        b.hasNonNull("name") ? b.get("name").asString() : null);
  }

  private JsonNode post(String path, Object body) {
    RestClient c = Objects.requireNonNull(promotion);
    String raw =
        Downstream.call(
            "promotion-service " + path,
            () ->
                c.post()
                    .uri(path)
                    .header("X-Source-System", "pricing-service")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
