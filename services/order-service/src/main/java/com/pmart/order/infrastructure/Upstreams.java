package com.pmart.order.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.application.OrderProperties;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.domain.ShippingAddress;
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

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Upstreams.class);

  public record Sku(
      String sku,
      String productId,
      String baseProductId,
      int weightG,
      String saleState,
      int unitsPerSku) {}

  public record PricedLine(String sku, long unitPrice, long lineAmount, long discountShare) {}

  /** 견적 쿠폰 결과(pricing 0.2.0). */
  public record PricedCoupon(
      String couponId, boolean applicable, @Nullable String reason, @Nullable String name) {}

  /**
   * @param itemAmount 기간 할인 뒤·쿠폰 전 상품액(최소 주문·무료 배달 판정)
   */
  public record Priced(
      int priceVersion,
      long itemAmount,
      Map<String, PricedLine> lines,
      long baseItemAmount,
      long promotionDiscount,
      long couponDiscount,
      @Nullable PricedCoupon coupon) {}

  /** 쇼핑 주소 판정 사본 + 주문 당시 배송지 사본(BR-37). 필수 필드가 비어 오면 shipping 은 null. */
  /**
   * @param contactMissing 배송지·프로필 모두 받는 분 연락처가 없다(BR-60) — 초안 CONTACT_REQUIRED
   */
  public record Address(
      String serviceability,
      @Nullable String mfcId,
      @Nullable ShippingAddress shipping,
      boolean contactMissing) {}

  /** 운영 캘린더 결과: configRequired 면 ranges 는 비어 있다. */
  public record CenterDay(boolean configRequired, boolean open, List<int[]> ranges) {}

  public record Slot(String start, String end, String availability) {}

  private final RestClient customer;
  private final RestClient product;
  private final RestClient pricing;
  private final RestClient center;
  private final RestClient capacity;
  private final RestClient content;
  private final RestClient inventory;
  private final ObjectMapper mapper;

  public Upstreams(RestClient.Builder builder, OrderProperties props, ObjectMapper mapper) {
    this.customer = builder.clone().baseUrl(props.customerBaseUrl()).build();
    this.product = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.pricing = builder.clone().baseUrl(props.pricingBaseUrl()).build();
    this.center = builder.clone().baseUrl(props.centerMasterBaseUrl()).build();
    this.capacity = builder.clone().baseUrl(props.capacityBaseUrl()).build();
    this.content = builder.clone().baseUrl(props.contentBaseUrl()).build();
    this.inventory = builder.clone().baseUrl(props.inventoryBaseUrl()).build();
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

  public Address shoppingAddress(
      String accountId, String shoppingAddressId, java.time.Instant now) {
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
    JsonNode a = n.path("address");
    JsonNode svc = a.path("serviceability");
    return new Address(
        svc.path("state").asString("UNKNOWN"),
        svc.hasNonNull("mfcId") ? svc.get("mfcId").asString() : null,
        shipping(a, now),
        !a.isMissingNode() && text(a, "receiverPhone") == null);
  }

  /**
   * customer 배송지 → 주문 당시 사본. 필수(도로명·상세·연락처)가 하나라도 없으면 null(지어내지 않는다). 받는 분·연락처는 customer 가 주소 → 프로필
   * 순으로 채운 값이고(BR-60), 받는 분 이름은 없을 수 있다.
   */
  private static @Nullable ShippingAddress shipping(JsonNode a, java.time.Instant now) {
    String road = text(a, "roadAddress");
    String detail = text(a, "detailAddress");
    String name = text(a, "receiverName");
    String phone = text(a, "receiverPhone");
    if (road == null || detail == null || phone == null) {
      return null;
    }
    JsonNode geo = a.path("geo");
    return new ShippingAddress(
        road,
        detail,
        text(a, "postalCode"),
        name,
        phone,
        text(a, "entranceMethod"),
        text(a, "entranceNote"),
        geo.hasNonNull("lat") ? geo.get("lat").asDouble() : null,
        geo.hasNonNull("lng") ? geo.get("lng").asDouble() : null,
        now);
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) && !n.get(field).asString().isBlank()
        ? n.get(field).asString()
        : null;
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

  // ---------- mfc-inventory ----------

  /**
   * 담당 센터의 출하 가능 수량(2026-11-16 — 주문 초안 재고 확인, 2026-11-18 mfc-inventory 0.24.0 IV-1 {@code
   * shippableAvailable}). 예약과 같은 소비기한 적격 판정이라, 기준일은 예약과 같게 예약 주문이면 도착 예정일(orchestration 의
   * promisedDate), 아니면 null(= 오늘). {@code shippableAvailable} 이 null 이면 판정 불가 — {@code available} 로
   * 바꾸지 않고 불명으로 둔다. 조회 실패·결과 불명은 null(불명 — 품절로 단정하지 않는다, Commerce 7장). 결제 단계 재고 예약이 최종 판정이다.
   */
  public @Nullable Map<String, OrderRules.Stock> availability(
      String mfcId, List<String> skus, @Nullable LocalDate deliveryDate) {
    try {
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
                            if (deliveryDate != null) {
                              u.queryParam("deliveryDate", deliveryDate.toString());
                            }
                            return u.build();
                          })
                      .retrieve()
                      .body(String.class));
      Map<String, OrderRules.Stock> out = new HashMap<>();
      for (JsonNode a : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        JsonNode v = a.path("shippableAvailable");
        out.put(
            a.path("sku").asString(),
            new OrderRules.Stock(
                v.isNumber() ? v.asInt() : null, a.path("handlingEnabled").asBoolean(true)));
      }
      return out;
    } catch (PmartException | HttpClientErrorException e) {
      log.warn("주문 초안 재고 확인 불명(진행 — 결제 단계 예약이 최종) — {}", e.getClass().getSimpleName());
      return null;
    }
  }

  // ---------- pricing ----------

  public Priced quote(
      String accountId, String mfcId, Map<String, Integer> qty, @Nullable String couponId) {
    List<Map<String, Object>> items = new ArrayList<>();
    qty.forEach((sku, q) -> items.add(Map.of("lineRef", sku, "sku", sku, "quantity", q)));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accountId", accountId);
    body.put("mfcId", mfcId);
    body.put("items", items);
    if (couponId != null) {
      body.put("couponIds", List.of(couponId));
    }
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
              l.path("lineAmount").asLong(),
              l.path("discountShare").asLong(0)));
    }
    JsonNode c = r.path("coupon");
    PricedCoupon coupon =
        c.isObject()
            ? new PricedCoupon(
                c.path("couponId").asString(),
                c.path("applicable").asBoolean(false),
                c.hasNonNull("reason") ? c.get("reason").asString() : null,
                c.hasNonNull("name") ? c.get("name").asString() : null)
            : null;
    long item = r.path("itemAmount").asLong();
    return new Priced(
        r.path("priceVersion").asInt(0),
        item,
        lines,
        r.path("baseItemAmount").asLong(item),
        r.path("promotionDiscount").asLong(0),
        r.path("couponDiscount").asLong(0),
        coupon);
  }

  // ---------- center-master · capacity ----------

  /** 센터 이름(center-master 내부 센터 조회 name, 2026-11-19 BR-62). 없는 센터는 null. */
  public @Nullable String centerName(String mfcId) {
    try {
      JsonNode n =
          get(center, "center-master-service center", "/api/v1/internal/centers/{id}", mfcId);
      String name = n.path("name").asString("");
      return name.isBlank() ? null : name;
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

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

  // ---------- product-content ----------

  /** 고객용 상품명·옵션 라벨(주문 당시 사본, BR-35). */
  public record ContentName(@Nullable String productName, @Nullable String optionLabel) {
    public static final ContentName NONE = new ContentName(null, null);
  }

  /**
   * product-content 주문 스냅샷의 상품명·옵션 라벨. 조회 실패(없음·장애·시간 초과)면 {@link ContentName#NONE} — 이름을 지어내지 않고,
   * 초안 생성을 막지 않는다(앱은 이름이 없을 때만 SKU 로 대신 보인다).
   */
  public ContentName contentName(@Nullable String productId, String sku) {
    if (productId == null) {
      return ContentName.NONE;
    }
    try {
      JsonNode n =
          get(
              content,
              "product-content-service snapshot",
              "/api/v1/internal/product-contents/{id}/snapshot?sku={sku}",
              productId,
              sku);
      return new ContentName(text(n, "name"), text(n, "optionLabel"));
    } catch (RuntimeException e) {
      log.warn("상품명 사본 조회 실패 productId={} sku={} — 이름 없이 진행: {}", productId, sku, e.toString());
      return ContentName.NONE;
    }
  }

  /**
   * 지금 상품의 대표 사진(BR-71 0.19.0) — product-content 공개 카드 {@code GET
   * /product-contents?productIds=}(100개씩). 게시 안 된 상품은 빠진다. 조회 실패면 빈 결과(주문 조회는 사진 없이) — 사진을 지어내지
   * 않는다.
   */
  public Map<String, String> productImages(java.util.Collection<String> productIds) {
    Map<String, String> out = new java.util.HashMap<>();
    List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(productIds));
    try {
      for (int from = 0; from < ids.size(); from += 100) {
        String joined = String.join(",", ids.subList(from, Math.min(ids.size(), from + 100)));
        JsonNode body =
            get(
                content,
                "product-content-service cards",
                "/api/v1/product-contents?productIds={ids}",
                joined);
        for (JsonNode card : body.path("items")) {
          String productId = text(card, "productId");
          String url = text(card.path("mainImage"), "url");
          if (productId != null && url != null && !url.isBlank()) {
            out.put(productId, url);
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("상품 사진 조회 실패({}개) — 사진 없이: {}", ids.size(), e.toString());
      return Map.of();
    }
    return out;
  }

  /** 0.20.0 O-11: product 공개 카테고리 트리(이름·부모). */
  public JsonNode categoryTree() {
    return get(product, "product-service categories", "/api/v1/categories/tree");
  }

  /** 0.20.0 O-11: product 상품 목록 한 쪽(productId·primaryCategoryId) — 커서 없으면 첫 쪽. */
  public JsonNode productsPage(@Nullable String cursor) {
    return cursor == null
        ? get(product, "product-service products", "/api/v1/products?size=200")
        : get(product, "product-service products", "/api/v1/products?size=200&cursor={c}", cursor);
  }

  private JsonNode get(RestClient client, String target, String uri, Object... vars) {
    String raw =
        Downstream.call(target, () -> client.get().uri(uri, vars).retrieve().body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
