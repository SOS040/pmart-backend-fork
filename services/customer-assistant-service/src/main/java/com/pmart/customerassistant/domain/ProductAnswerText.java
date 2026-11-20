package com.pmart.customerassistant.domain;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 상품 검색 결과를 정해진 문장으로(고객용, 2026-11-20 사용자 "상품 질문만 정해진 문장", "전체 기준 — 센터 취급 여부는 답하지 않음"). 모델을 거치지 않으므로
 * 가격·상태를 지어내지 않는다. 주문 상태 답(OrderStatusText)과 같은 방식.
 */
public final class ProductAnswerText {

  /**
   * 검색 결과 한 줄.
   *
   * @param basePrice 기본가(원). 가격이 아직 없으면 null — 0원으로 쓰지 않는다
   * @param saleState ON_SALE·TEMPORARILY_SOLD_OUT·SALE_STOPPED·UNKNOWN
   * @param carried 고객용은 센터를 주지 않으므로 늘 null
   */
  public record Item(
      String name, @Nullable Long basePrice, String saleState, @Nullable Boolean carried) {}

  private ProductAnswerText() {}

  public static String saleLabel(String saleState) {
    return switch (saleState) {
      case "ON_SALE" -> "판매 중";
      case "TEMPORARILY_SOLD_OUT" -> "일시품절";
      case "SALE_STOPPED" -> "판매 중지";
      default -> "판매 상태 확인 필요";
    };
  }

  public static String price(@Nullable Long won) {
    return won == null ? "가격 준비 중" : NumberFormat.getInstance(Locale.KOREA).format(won) + "원";
  }

  /** 고객용 답. */
  public static String customer(String keyword, List<Item> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("'").append(keyword).append("'(으)로 찾은 상품이에요.\n");
    for (Item i : items) {
      sb.append("- ").append(i.name()).append(" · ").append(price(i.basePrice()));
      sb.append(" · ").append(saleLabel(i.saleState())).append('\n');
    }
    sb.append("가격과 판매 상태는 바뀔 수 있고, 주소에 따라 받아 볼 수 없는 상품도 있어요. ");
    sb.append("정확한 내용은 앱에서 상품을 검색해 확인해 주세요.");
    return sb.toString();
  }
}
