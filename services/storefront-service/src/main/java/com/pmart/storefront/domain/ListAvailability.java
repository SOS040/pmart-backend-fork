package com.pmart.storefront.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 목록 카드의 판매 가능 여부(계약 0.13.0 — 검색 0.10.0 과 같은 판정, 2026-11-23 사용자 "모든 옵션 품절일 때만"). 옵션마다
 * product-content 가 준 센터 가용 사본(carried·state)으로 상품 하나의 값을 정한다. 모르면 품절이라고 하지 않는다(UNKNOWN).
 */
public final class ListAvailability {

  public static final String AVAILABLE = "AVAILABLE";
  public static final String SOLD_OUT = "SOLD_OUT";
  public static final String UNKNOWN = "UNKNOWN";

  /** 옵션 하나의 사본. carried 가 null 이면 조회 실패(모름). state 는 AVAILABLE·LOW·SOLD_OUT·UNKNOWN. */
  public record Option(@Nullable Boolean carried, @Nullable String state) {}

  private ListAvailability() {}

  /**
   * 그 센터가 취급하는(carried=true) 옵션 중 하나라도 AVAILABLE·LOW 면 AVAILABLE, 취급 옵션이 있고 모두 SOLD_OUT 이면
   * SOLD_OUT, 그 밖(취급 옵션 없음·하나라도 모름이고 팔 수 있는 것 없음)은 UNKNOWN.
   */
  public static String of(List<Option> options) {
    boolean anyCarried = false;
    boolean allSoldOut = true;
    for (Option o : options) {
      if (!Boolean.TRUE.equals(o.carried())) {
        continue;
      }
      anyCarried = true;
      if (AVAILABLE.equals(o.state()) || "LOW".equals(o.state())) {
        return AVAILABLE;
      }
      if (!SOLD_OUT.equals(o.state())) {
        allSoldOut = false;
      }
    }
    return anyCarried && allSoldOut ? SOLD_OUT : UNKNOWN;
  }
}
