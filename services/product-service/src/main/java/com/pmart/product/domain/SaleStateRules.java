package com.pmart.product.domain;

import java.util.Set;

/**
 * 전사 판매 상태 규칙(BO-03 4), 사용자 결정 2026-10-30).
 *
 * <ul>
 *   <li>판매중지(SALE_STOPPED)로 바꿀 때만 PRODUCT 승인이 필요하다. 일시품절·판매 재개는 MD 가 바로 바꾼다.
 *   <li>센터별 취급 ON/OFF 와는 별개다(inventory 서비스 책임).
 * </ul>
 */
public final class SaleStateRules {

  public static final String ON_SALE = "ON_SALE";
  public static final String TEMPORARILY_SOLD_OUT = "TEMPORARILY_SOLD_OUT";
  public static final String SALE_STOPPED = "SALE_STOPPED";
  public static final Set<String> ALL = Set.of(ON_SALE, TEMPORARILY_SOLD_OUT, SALE_STOPPED);

  private SaleStateRules() {}

  public static boolean requiresApproval(String to) {
    return SALE_STOPPED.equals(to);
  }

  /** API 판매 상태 → 이벤트 ProductSaleStateChanged.state (ON_SALE / TEMP_OUT / DISCONTINUED). */
  public static String eventState(String state) {
    return switch (state) {
      case TEMPORARILY_SOLD_OUT -> "TEMP_OUT";
      case SALE_STOPPED -> "DISCONTINUED";
      default -> ON_SALE;
    };
  }
}
