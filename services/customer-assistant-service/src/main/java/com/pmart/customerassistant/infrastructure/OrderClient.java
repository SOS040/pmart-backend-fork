package com.pmart.customerassistant.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.customerassistant.application.AssistantProperties;
import com.pmart.customerassistant.domain.OrderStatusText;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-service 주문 조회(동기 1 hop, 호출한 고객의 토큰을 그대로 전달 — 사용자 결정 2026-11-09 C6). order {@code GET
 * /orders/{id}} 는 남의·없는 주문을 404 로 돌려주므로 200 이면 본인 주문이다. 연결 실패·5xx 는 {@link Downstream} 이 503 으로
 * 바꾼다.
 *
 * <p>order 응답 모델을 그대로 쓰지 않고 필요한 값만 {@link OrderView} 로 옮긴다(주소·결제 정보는 옮기지 않는다).
 */
@Component
public class OrderClient {

  public static final String ORDER = "order-service";

  /** 고객에게 보일 주문 요약. 주소·결제·금액 내역은 담지 않는다. */
  public record OrderView(
      String orderId,
      String status,
      @Nullable Instant orderedAt,
      @Nullable Integer itemCount,
      @Nullable Long totalAmount,
      OrderStatusText.@Nullable Window window) {

    public String statusLabel() {
      return OrderStatusText.label(status);
    }
  }

  private final RestClient client;
  private final ObjectMapper json;

  public OrderClient(RestClient.Builder builder, AssistantProperties props, ObjectMapper json) {
    this.client =
        builder
            .clone()
            .baseUrl(
                Objects.requireNonNullElse(
                    props.getCustomer().getOrderServiceBaseUrl(), "http://unconfigured"))
            .build();
    this.json = json;
  }

  /** 본인 주문 상세. 남의·없는 주문은 비어 있다. */
  public Optional<OrderView> get(String orderId) {
    String auth = currentAuthorization();
    String body =
        Downstream.call(
            ORDER,
            () ->
                client
                    .get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .headers(h -> auth(h, auth))
                    .exchange(
                        (req, res) -> {
                          int s = res.getStatusCode().value();
                          if (s >= 500) {
                            throw new IllegalStateException("order-service " + s);
                          }
                          return s == 200 ? new String(res.getBody().readAllBytes()) : null;
                        }));
    if (body == null) {
      return Optional.empty();
    }
    JsonNode n = json.readTree(body);
    OrderStatusText.Window window = null;
    JsonNode w = n.path("arrivalWindow");
    if (w.hasNonNull("date") && w.hasNonNull("start") && w.hasNonNull("end")) {
      window =
          new OrderStatusText.Window(
              LocalDate.parse(w.get("date").asString()),
              w.get("start").asString(),
              w.get("end").asString());
    }
    Integer items = null;
    if (n.path("lines").isArray()) {
      int sum = 0;
      for (JsonNode l : n.get("lines")) {
        sum += l.path("originalQty").asInt(0);
      }
      items = sum;
    }
    return Optional.of(
        new OrderView(
            text(n, "orderId", orderId),
            text(n, "status", "UNKNOWN"),
            instant(n.path("confirmedAt")),
            items,
            null,
            window));
  }

  /** 본인 최근 주문(최근 확정 순). */
  public List<OrderView> recent(int size) {
    String auth = currentAuthorization();
    String body =
        Downstream.call(
            ORDER,
            () ->
                client
                    .get()
                    .uri(u -> u.path("/api/v1/orders").queryParam("size", size).build())
                    .headers(h -> auth(h, auth))
                    .exchange(
                        (req, res) -> {
                          int s = res.getStatusCode().value();
                          if (s >= 500) {
                            throw new IllegalStateException("order-service " + s);
                          }
                          if (s != 200) {
                            throw new IllegalStateException("order-service 주문 목록 " + s);
                          }
                          return new String(res.getBody().readAllBytes());
                        }));
    List<OrderView> out = new ArrayList<>();
    for (JsonNode o : json.readTree(body).path("items")) {
      JsonNode amount = o.path("totalAmount");
      Long total = null;
      if (amount.isNumber()) {
        total = amount.asLong();
      } else if (amount.hasNonNull("amount")) {
        total = amount.get("amount").asLong();
      }
      out.add(
          new OrderView(
              text(o, "orderId", ""),
              text(o, "status", "UNKNOWN"),
              instant(o.path("orderedAt")),
              o.hasNonNull("itemCount") ? o.get("itemCount").asInt() : null,
              total,
              null));
      if (out.size() >= size) {
        break;
      }
    }
    return out;
  }

  private static void auth(HttpHeaders h, @Nullable String auth) {
    if (auth != null) {
      h.set(HttpHeaders.AUTHORIZATION, auth);
    }
  }

  private static String text(JsonNode n, String field, String dflt) {
    return n.hasNonNull(field) ? n.get(field).asString() : dflt;
  }

  private static @Nullable Instant instant(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) {
      return null;
    }
    try {
      return java.time.OffsetDateTime.parse(n.asString()).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      return a.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
