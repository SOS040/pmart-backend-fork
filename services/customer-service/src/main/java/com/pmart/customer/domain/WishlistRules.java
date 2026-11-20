package com.pmart.customer.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 찜 목록 표시 규칙(2026-11-20 ADR-0081). product-content 카드의 옵션으로 대표 판매 상태·가격을 정한다.
 *
 * <ul>
 *   <li>카드 없음(게시 안 됨·조회 실패) → UNKNOWN, 가격 null
 *   <li>판매 중(ON_SALE) 옵션이 하나도 없음 → 모두 SALE_STOPPED 면 ENDED, 그 밖(일시 품절 포함) SOLD_OUT
 *   <li>판매 중 옵션이 있고 그중 하나라도 담당 센터 가용이 SOLD_OUT 이 아니면(AVAILABLE·LOW·UNKNOWN·센터 모름) → ON_SALE
 *   <li>판매 중 옵션이 모두 담당 센터 SOLD_OUT → SOLD_OUT
 *   <li>대표 가격 = 판매 중 옵션 중 첫 옵션의 기본가(없으면 첫 옵션의 기본가, 그래도 없으면 null — 0원으로 지어내지 않음)
 * </ul>
 */
public final class WishlistRules {

  public static final String ON_SALE = "ON_SALE";
  public static final String SOLD_OUT = "SOLD_OUT";
  public static final String ENDED = "ENDED";
  public static final String UNKNOWN = "UNKNOWN";

  /** 카드 옵션 한 줄(필요한 칸만). */
  public record Option(
      @Nullable String saleState, @Nullable String availabilityState, @Nullable Long basePrice) {}

  public record Display(String saleState, @Nullable Long price) {}

  private WishlistRules() {}

  public static Display display(@Nullable List<Option> options) {
    if (options == null) {
      return new Display(UNKNOWN, null);
    }
    List<Option> selling = options.stream().filter(o -> "ON_SALE".equals(o.saleState())).toList();
    Long price =
        selling.stream()
            .map(Option::basePrice)
            .filter(p -> p != null)
            .findFirst()
            .orElse(
                options.stream()
                    .map(Option::basePrice)
                    .filter(p -> p != null)
                    .findFirst()
                    .orElse(null));
    if (options.isEmpty()) {
      return new Display(UNKNOWN, null);
    }
    if (selling.isEmpty()) {
      boolean stopped = options.stream().allMatch(o -> "SALE_STOPPED".equals(o.saleState()));
      return new Display(stopped ? ENDED : SOLD_OUT, price);
    }
    boolean anyBuyable = selling.stream().anyMatch(o -> !"SOLD_OUT".equals(o.availabilityState()));
    return new Display(anyBuyable ? ON_SALE : SOLD_OUT, price);
  }
}
