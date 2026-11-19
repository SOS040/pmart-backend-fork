package com.pmart.notification.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.notification.application.NotificationProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order {@code GET /internal/orders/{id}}(내부 호출) — 받는 고객(accountId)과 품목 상품명. 사건에 고객이 없을 때 쓴다. 없으면
 * null, 연결 실패·5xx 는 예외(사건 재시도).
 */
@Component
public class OrderClient {

  /** status 는 주문 상태(리뷰 요청 — 취소된 주문 거르기, 2026-11-18). 응답에 없으면 null. */
  /**
   * @param firstProductName 첫 주문행 상품명 사본(없으면 null)
   * @param lineCount 주문행 수
   * @param arrivalDate 예약배달 도착 날짜(Asia/Seoul, order 0.14.1) — 없으면 null
   */
  public record OrderInfo(
      String accountId,
      Map<String, String> productNames,
      @Nullable String status,
      @Nullable String firstProductName,
      int lineCount,
      @Nullable String deliveryMode,
      @Nullable String arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd) {

    public OrderInfo(String accountId, Map<String, String> productNames, @Nullable String status) {
      this(accountId, productNames, status, null, 0, null, null, null, null);
    }
  }

  private final RestClient client;
  private final ObjectMapper mapper;

  public OrderClient(
      RestClient.Builder builder, NotificationProperties props, ObjectMapper mapper) {
    this.client =
        builder
            .clone()
            .baseUrl(Objects.requireNonNullElse(props.orderBaseUrl(), "http://unconfigured"))
            .build();
    this.mapper = mapper;
  }

  public @Nullable OrderInfo order(String orderId) {
    String raw;
    try {
      raw =
          Downstream.call(
              "order-service",
              () ->
                  client
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
    Map<String, String> names = new HashMap<>();
    for (JsonNode l : n.path("lines")) {
      JsonNode name = l.get("productName");
      if (name != null && !name.isNull()) {
        names.put(l.path("orderLineId").asString(), name.asString());
      }
    }
    String account = n.path("accountId").asString(null);
    if (account == null) {
      return null;
    }
    JsonNode first = n.path("lines").path(0).path("productName");
    JsonNode w = n.path("arrivalWindow");
    return new OrderInfo(
        account,
        names,
        n.path("status").asString(null),
        first.isString() ? first.asString() : null,
        n.path("lines").size(),
        n.path("deliveryMode").asString(null),
        w.path("date").asString(null),
        w.path("start").asString(null),
        w.path("end").asString(null));
  }
}
