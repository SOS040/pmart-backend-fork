package com.pmart.order.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.application.OrderProperties;
import com.pmart.order.domain.OrderRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 초안이 부르는 정본들. {@code /internal/} 은 내부 호출(토큰 없음)(공용 RestClient 가 붙인다):
 * customer·product·pricing·center-master. 예약 슬롯은 공개 경로(capacity {@code /slots})라 요청한 사용자 토큰이
 * 전파된다(비회원도 허용 경로).
 */
@Component
public class Upstreams {

  public record Sku(
      String sku,
      String productId,
      String baseProductId,
      int weightG,
      String saleState,
      int unitsPerSku) {}

  public record PricedLine(String sku, long unitPrice, long lineAmount) {}

  public record Priced(int priceVersion, long itemAmount, Map<String, PricedLine> lines) {}

  public record Address(String serviceability, @Nullable String mfcId) {}

  /** 운영 캘린더 결과: configRequired 면 ranges 는 비어 있다. */
  public record CenterDay(boolean configRequired, boolean open, List<int[]> ranges) {}

  public record Slot(String start, String end, String availability) {}

  private final RestClient customer;
  private final RestClient product;
  private final RestClient pricing;
  private final RestClient center;
  private final RestClient capacity;
  private final ObjectMapper mapper;

  public Upstreams(RestClient.Builder builder, OrderProperties props, ObjectMapper mapper) {
    this.customer = builder.clone().baseUrl(props.customerBaseUrl()).build();
    this.product = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.pricing = builder.clone().baseUrl(props.pricingBaseUrl()).build();
    this.center = builder.clone().baseUrl(props.centerMasterBaseUrl()).build();
    this.capacity = builder.clone().baseUrl(props.capacityBaseUrl()).build();
    this.mapper = mapper;
  }

  // ---------- customer ----------

  /** 구매 가능 계정인가(없는 계정은 false). */
  public boolean purchasable(String accountId) {
    try {
      JsonNode n =
          get(
              customer,
              "customer-service accounts",
              "/api/v1/internal/accounts/{id}/status",
              accountId);
      return n.path("purchasable").asBoolean(false);
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
  }

  public Address shoppingAddress(String accountId, String shoppingAddressId) {
    JsonNode n;
    try {
      n =
          get(
              customer,
              "customer-service shopping-context",
              "/api/v1/internal/accounts/{id}/shopping-context?shoppingAddressId={a}",
              accountId,
              shoppingAddressId);
    } catch (HttpClientErrorException.NotFound e) {
      throw new PmartException(
          "ADDRESS_NOT_FOUND",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "배송지를 찾을 수 없습니다.",
          false,
          List.of());
    }
    JsonNode svc = n.path("address").path("serviceability");
    return new Address(
        svc.path("state").asString("UNKNOWN"),
        svc.hasNonNull("mfcId") ? svc.get("mfcId").asString() : null);
  }

  public boolean firstPurchaseCandidate(String accountId) {
    return get(
            customer,
            "customer-service first-purchase-checks",
            "/api/v1/internal/accounts/{id}/first-purchase-checks",
            accountId)
        .path("firstPurchaseCandidate")
        .asBoolean(false);
  }

  // ---------- product ----------

  public Map<String, Sku> skus(List<String> skus) {
    JsonNode n =
        get(
            product,
            "product-service skus",
            "/api/v1/internal/skus?skus={s}",
            String.join(",", skus));
    Map<String, Sku> out = new HashMap<>();
    for (JsonNode s : n.path("items")) {
      out.put(
          s.path("sku").asString(),
          new Sku(
              s.path("sku").asString(),
              s.path("productId").asString(),
              s.path("baseProductId").asString(),
              s.path("weightG").asInt(0),
              s.path("saleState").asString("UNKNOWN"),
              s.path("unitsPerSku").asInt(1)));
    }
    return out;
  }

  /** 위반 첫 건 또는 null. 상한 정책값이 없으면 CONFIG_REQUIRED. */
  public @Nullable JsonNode purchaseLimitViolation(String accountId, Map<String, Integer> qty) {
    List<Map<String, Object>> items = new ArrayList<>();
    qty.forEach((sku, q) -> items.add(Map.of("sku", sku, "quantity", q)));
    JsonNode r;
    try {
      r =
          Downstream.call(
              "product-service purchase-limit-checks",
              () ->
                  mapper.readTree(
                      product
                          .post()
                          .uri("/api/v1/internal/purchase-limit-checks")
                          .contentType(MediaType.APPLICATION_JSON)
                          .body(Map.of("accountId", accountId, "items", items))
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.UnprocessableContent e) {
      throw PmartException.configRequired("product.purchase-limit-units");
    }
    return r.path("passed").asBoolean(false) ? null : r.path("violations").path(0);
  }

  // ---------- pricing ----------

  public Priced quote(String accountId, String mfcId, Map<String, Integer> qty) {
    List<Map<String, Object>> items = new ArrayList<>();
    qty.forEach((sku, q) -> items.add(Map.of("lineRef", sku, "sku", sku, "quantity", q)));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accountId", accountId);
    body.put("mfcId", mfcId);
    body.put("items", items);
    JsonNode r;
    try {
      r =
          Downstream.call(
              "pricing-service quotes",
              () ->
                  mapper.readTree(
                      pricing
                          .post()
                          .uri("/api/v1/internal/quotes")
                          .header("Idempotency-Key", UUID.randomUUID().toString())
                          .contentType(MediaType.APPLICATION_JSON)
                          .body(body)
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.UnprocessableContent e) {
      throw new PmartException(
          "PRICE_NOT_SET",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "가격이 정해지지 않은 상품이 있습니다. 그 상품을 빼고 다시 시도하세요.",
          false,
          List.of());
    }
    Map<String, PricedLine> lines = new HashMap<>();
    for (JsonNode l : r.path("lines")) {
      lines.put(
          l.path("sku").asString(),
          new PricedLine(
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("lineAmount").asLong()));
    }
    return new Priced(r.path("priceVersion").asInt(0), r.path("itemAmount").asLong(), lines);
  }

  // ---------- center-master · capacity ----------

  public CenterDay centerDay(String mfcId, LocalDate date) {
    JsonNode n;
    try {
      n =
          get(
              center,
              "center-master-service calendar",
              "/api/v1/internal/centers/{id}/calendar?date={d}&kind=CUSTOMER_ORDER",
              mfcId,
              date);
    } catch (HttpClientErrorException.UnprocessableContent | HttpClientErrorException.NotFound e) {
      return new CenterDay(true, false, List.of());
    }
    List<int[]> ranges = new ArrayList<>();
    n.path("ranges")
        .forEach(
            r ->
                ranges.add(
                    new int[] {
                      OrderRules.minutes(r.path("start").asString()),
                      OrderRules.minutes(r.path("end").asString())
                    }));
    return new CenterDay(false, n.path("open").asBoolean(false), ranges);
  }

  /** 예약 슬롯. 정원·시간표 미설정(422)이면 빈 목록이 아니라 예외(CONFIG_REQUIRED). */
  public List<Slot> slots(String mfcId, LocalDate date) {
    JsonNode n;
    try {
      n =
          get(
              capacity,
              "delivery-capacity-service slots",
              "/api/v1/slots?mfcId={m}&date={d}",
              mfcId,
              date);
    } catch (HttpClientErrorException.UnprocessableContent e) {
      throw PmartException.configRequired("delivery-capacity.slots." + mfcId);
    }
    List<Slot> out = new ArrayList<>();
    for (JsonNode s : n.path("slots")) {
      out.add(
          new Slot(
              s.path("window").path("start").asString(),
              s.path("window").path("end").asString(),
              s.path("availability").asString("UNAVAILABLE")));
    }
    return out;
  }

  private JsonNode get(RestClient client, String target, String uri, Object... vars) {
    String raw =
        Downstream.call(target, () -> client.get().uri(uri, vars).retrieve().body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
