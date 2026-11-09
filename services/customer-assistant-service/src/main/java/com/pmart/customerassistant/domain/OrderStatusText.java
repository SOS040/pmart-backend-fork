package com.pmart.customerassistant.domain;

import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 주문 상태 → 고객에게 보일 이름과 정해진 안내 문장(ORDER_STATUS). 주문 상태는 모델에 넘기지 않고 여기서 문장으로 만든다 — 모델이 상태·시간을 지어내지 않게.
 *
 * <p>상태 이름은 order-service {@code OrderStatus}(contracts/openapi/order-service.yaml) 그대로 받는다. 모르는
 * 상태는 "확인 중"으로 보인다(지어내지 않음).
 */
public final class OrderStatusText {

  private static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry("DRAFT", "주문 작성 중"),
          Map.entry("RESOURCE_PENDING", "주문 확인 중"),
          Map.entry("PAYMENT_PENDING", "결제 확인 중"),
          Map.entry("PAYMENT_UNKNOWN", "결제 확인 중"),
          Map.entry("CONFIRMED", "주문 완료"),
          Map.entry("PREPARING", "상품 준비 중"),
          Map.entry("PARTIALLY_HANDED_OVER", "일부 상품 배송 출발"),
          Map.entry("IN_DELIVERY", "배송 중"),
          Map.entry("PARTIALLY_DELIVERED", "일부 배송 완료"),
          Map.entry("COMPLETED", "배송 완료"),
          Map.entry("CANCELLED", "주문 취소"),
          Map.entry("EXPIRED", "주문 만료"));

  /** 도착 예정 시간을 말해도 되는 상태(끝난 주문·결제 전에는 말하지 않는다). */
  private static final java.util.Set<String> SHOW_WINDOW =
      java.util.Set.of(
          "CONFIRMED", "PREPARING", "PARTIALLY_HANDED_OVER", "IN_DELIVERY", "PARTIALLY_DELIVERED");

  /** 도착 예정 시간대(Asia/Seoul). */
  public record Window(LocalDate date, String start, String end) {}

  private OrderStatusText() {}

  public static String label(@Nullable String status) {
    return status == null ? "확인 중" : LABELS.getOrDefault(status, "확인 중");
  }

  /** 고른 주문의 지금 상태 안내. 문장은 고정 — 상태 이름과 도착 예정 시간만 채운다. */
  public static String sentence(@Nullable String status, @Nullable Window window) {
    StringBuilder sb = new StringBuilder("고르신 주문은 지금 '").append(label(status)).append("' 상태예요.");
    if (window != null && status != null && SHOW_WINDOW.contains(status)) {
      sb.append(" 도착 예정 시간은 ")
          .append(window.date().getMonthValue())
          .append("월 ")
          .append(window.date().getDayOfMonth())
          .append("일 ")
          .append(window.start())
          .append('~')
          .append(window.end())
          .append("이에요.");
    }
    sb.append(" 자세한 진행 상황은 주문 상세에서 볼 수 있어요.");
    return sb.toString();
  }
}
