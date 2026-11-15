package com.pmart.review.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.review.application.ReviewProperties;
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
 * order·customer {@code /internal} 조회(서비스 간 인증 없음 — ADR-0026). 연결 실패·5xx 는 {@link Downstream} 이
 * 503/UNKNOWN 으로 바꾼다. 없음(404)은 null.
 */
@Component
public class Upstreams {

  private static final Logger log = LoggerFactory.getLogger(Upstreams.class);

  public static final String ORDER = "order-service";
  public static final String CUSTOMER = "customer-service";

  /** 주문 원장의 한 품목(리뷰가 쓰는 값만). */
  public record OrderLine(
      String orderLineId,
      @Nullable String sku,
      @Nullable String productId,
      @Nullable String productName,
      @Nullable Integer returnedQty) {}

  public record Order(String orderId, String accountId, java.util.List<OrderLine> lines) {
    public @Nullable OrderLine line(String orderLineId) {
      return lines.stream()
          .filter(l -> l.orderLineId().equals(orderLineId))
          .findFirst()
          .orElse(null);
    }
  }

  private final RestClient order;
  private final RestClient customer;
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
    this.mapper = mapper;
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
    java.util.List<OrderLine> lines = new java.util.ArrayList<>();
    for (JsonNode l : n.path("lines")) {
      lines.add(
          new OrderLine(
              l.path("orderLineId").asString(),
              text(l, "sku"),
              text(l, "productId"),
              text(l, "productName"),
              l.hasNonNull("returnedQty") ? l.get("returnedQty").asInt() : null));
    }
    return new Order(n.path("orderId").asString(orderId), n.path("accountId").asString(), lines);
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
