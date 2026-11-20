package com.pmart.assistant.domain;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 상품 검색 결과를 정해진 문장으로(운영자용, 2026-11-20 사용자 "상품 질문만 정해진 문장", "상품 정보만 답함"). 모델을 거치지 않으므로 가격·상태를 지어내지
 * 않는다. 실시간 재고 수량·주문 상태는 답하지 않는다(ADR-0036 결정 1 — ADR-0083 가 상품 정보만 넓힘).
 */
public final class ProductAnswerText {

  /**
   * 검색 결과 한 줄.
   *
   * @param basePrice 기본가(원). 가격이 아직 없으면 null — 0원으로 쓰지 않는다
   * @param saleState ON_SALE·TEMPORARILY_SOLD_OUT·SALE_STOPPED·UNKNOWN
   * @param carried 질문 화면의 센터 취급 여부. 센터를 주지 않았으면 null
   */
  public record Item(
      String name, @Nullable Long basePrice, String saleState, @Nullable Boolean carried) {}

  private ProductAnswerText() {}

  public static String saleLabel(String saleState) {
    return switch (saleState) {
      case "ON_SALE" -> "판매 중";
      case "TEMPORARILY_SOLD_OUT" -> "일시품절";
      case "SALE_STOPPED" -> "판매중지";
      default -> "판매 상태 확인 필요";
    };
  }

  public static String price(@Nullable Long won) {
    return won == null ? "가격 미등록" : NumberFormat.getInstance(Locale.KOREA).format(won) + "원";
  }

  /** 운영자용 답. {@code centerGiven} 이면 줄마다 그 센터 취급 여부를 붙인다. */
  public static String operator(String keyword, List<Item> items, long total, boolean centerGiven) {
    StringBuilder sb = new StringBuilder();
    sb.append("상품 검색에서 '").append(keyword).append("'(으)로 찾은 상품입니다");
    if (total > items.size()) {
      sb.append(" (전체 ").append(total).append("개 중 ").append(items.size()).append("개)");
    }
    sb.append(".\n");
    for (Item i : items) {
      sb.append("- ").append(i.name()).append(" · 기본가 ").append(price(i.basePrice()));
      sb.append(" · ").append(saleLabel(i.saleState()));
      if (centerGiven && i.carried() != null) {
        sb.append(" · ").append(i.carried() ? "이 센터 취급" : "이 센터 미취급");
      }
      sb.append('\n');
    }
    sb.append("가격·판매 상태는 상품 검색 색인 기준이며 반영이 조금 늦을 수 있습니다. ");
    sb.append("실시간 재고 수량과 주문 상태는 이 기능으로 확인할 수 없으니 해당 업무 화면에서 조회하세요.");
    return sb.toString();
  }
}
