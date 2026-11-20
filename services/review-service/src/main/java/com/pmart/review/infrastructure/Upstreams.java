package com.pmart.review.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.review.application.ReviewProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order·customer·center-master {@code /internal} 조회와 product-content 공개 카드 조회(서비스 간 인증 없음 —
 * ADR-0026). 연결 실패·5xx 는 {@link Downstream} 이 503/UNKNOWN 으로 바꾼다. 없음(404)은 null.
 */
@Component
public class Upstreams {

  private static final Logger log = LoggerFactory.getLogger(Upstreams.class);

  public static final String ORDER = "order-service";
  public static final String CUSTOMER = "customer-service";
  public static final String CENTER = "center-master-service";
  public static final String CONTENT = "product-content-service";

  /** 카드 일괄 조회 한 번의 상한(product-content 계약 maxItems 100). */
  static final int CARD_BATCH = 100;

  /** 주문 원장의 한 품목(리뷰가 쓰는 값만). */
  public record OrderLine(
      String orderLineId,
      @Nullable String sku,
      @Nullable String productId,
      @Nullable String productName,
      @Nullable Integer returnedQty) {}

  /**
   * 주문 원장 중 리뷰가 쓰는 값. confirmedAt(주문 확정 시각)·mfcId 는 2026-11-20 BR-67 — 리뷰관리 주문 묶음 머리. 응답에 없으면 null.
   */
  public record Order(
      String orderId,
      String accountId,
      @Nullable Instant confirmedAt,
      @Nullable String mfcId,
      List<OrderLine> lines) {
    public @Nullable OrderLine line(String orderLineId) {
      return lines.stream()
          .filter(l -> l.orderLineId().equals(orderLineId))
          .findFirst()
          .orElse(null);
    }
  }

  private final RestClient order;
  private final RestClient customer;
  private final @Nullable RestClient center;
  private final @Nullable RestClient content;
  private final ObjectMapper mapper;

  public Upstreams(RestClient.Builder builder, ReviewProperties props, ObjectMapper mapper) {
    this.order =
        builder
            .clone()
            .baseUrl(Objects.requireNonNullElse(props.orderBaseUrl(), "http://unconfigured"))
            .build();
    this.customer =
        builder
            .clone()
            .baseUrl(Objects.requireNonNullElse(props.customerBaseUrl(), "http://unconfigured"))
            .build();
    this.center = client(builder, props.centerMasterBaseUrl());
    this.content = client(builder, props.contentBaseUrl());
    this.mapper = mapper;
  }

  /** 설정이 없으면 null — 그 값은 부르지 않고 비워 둔다(표시용 부가 값이라 기동을 막지 않는다). */
  private static @Nullable RestClient client(RestClient.Builder builder, @Nullable String baseUrl) {
    return baseUrl == null || baseUrl.isBlank() ? null : builder.clone().baseUrl(baseUrl).build();
  }

  /** 주문 원장(계정·품목). 없으면 null. */
  public @Nullable Order order(String orderId) {
    String raw;
    try {
      raw =
          Downstream.call(
              ORDER,
              () ->
                  order
                      .get()
                      .uri("/api/v1/internal/orders/{id}", orderId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
    if (raw == null) {
      return null;
    }
    JsonNode n = mapper.readTree(raw);
    List<OrderLine> lines = new ArrayList<>();
    for (JsonNode l : n.path("lines")) {
      lines.add(
          new OrderLine(
              l.path("orderLineId").asString(),
              text(l, "sku"),
              text(l, "productId"),
              text(l, "productName"),
              l.hasNonNull("returnedQty") ? l.get("returnedQty").asInt() : null));
    }
    String confirmed = text(n, "confirmedAt");
    return new Order(
        n.path("orderId").asString(orderId),
        n.path("accountId").asString(),
        confirmed == null ? null : OffsetDateTime.parse(confirmed).toInstant(),
        text(n, "mfcId"),
        lines);
  }

  /**
   * 센터 표시 이름(center-master 내부 센터 조회 name). 없는 센터(404)·빈 이름은 null. 연결 실패·5xx 는 예외 — 호출자가 null 로 둔다.
   * 설정이 없으면 null.
   */
  public @Nullable String centerName(String mfcId) {
    RestClient c = center;
    if (c == null) {
      return null;
    }
    try {
      String raw =
          Downstream.call(
              CENTER,
              () ->
                  c.get()
                      .uri("/api/v1/internal/centers/{id}", mfcId)
                      .retrieve()
                      .body(String.class));
      String name = raw == null ? null : text(mapper.readTree(raw), "name");
      return name == null || name.isBlank() ? null : name;
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /** 상품 카드 중 리뷰 자격 목록이 쓰는 값 — 지금 대표 사진 주소와 SKU 별 옵션 라벨(콘텐츠에 있는 것만). */
  public record ProductCard(@Nullable String imageUrl, Map<String, String> optionLabels) {}

  /**
   * 게시된 상품 카드 일괄 조회(product-content {@code GET /product-contents?productIds=}, 100개씩). 미게시·없는 상품은
   * 결과에서 빠진다. 실패는 예외 — 호출자가 사진을 비워 둔다. 설정이 없으면 빈 결과.
   */
  public Map<String, ProductCard> productCards(Collection<String> productIds) {
    RestClient c = content;
    Map<String, ProductCard> out = new HashMap<>();
    if (c == null || productIds.isEmpty()) {
      return out;
    }
    List<String> ids = List.copyOf(new LinkedHashSet<>(productIds));
    for (int from = 0; from < ids.size(); from += CARD_BATCH) {
      String joined = String.join(",", ids.subList(from, Math.min(ids.size(), from + CARD_BATCH)));
      String raw =
          Downstream.call(
              CONTENT,
              () ->
                  c.get()
                      .uri("/api/v1/product-contents?productIds={ids}", joined)
                      .retrieve()
                      .body(String.class));
      for (JsonNode card : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        String productId = text(card, "productId");
        if (productId == null) {
          continue;
        }
        Map<String, String> labels = new HashMap<>();
        for (JsonNode o : card.path("options")) {
          String sku = text(o, "sku");
          String label = text(o, "label");
          if (sku != null && label != null && !label.isBlank()) {
            labels.put(sku, label);
          }
        }
        String url = text(card.path("mainImage"), "url");
        out.put(productId, new ProductCard(url == null || url.isBlank() ? null : url, labels));
      }
    }
    return out;
  }

  /** 작성자 표시(가린 별명). 못 읽으면 null — 호출자가 기본 표시를 쓴다(리뷰 작성을 막지 않는다). */
  public @Nullable String authorName(String accountId) {
    try {
      String raw =
          Downstream.call(
              CUSTOMER,
              () ->
                  customer
                      .get()
                      .uri("/api/v1/internal/accounts/{id}/review-author", accountId)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : text(mapper.readTree(raw), "displayName");
    } catch (RuntimeException e) {
      log.warn("작성자 표시를 읽지 못함 — 기본 표시: {}", e.toString());
      return null;
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }
}
