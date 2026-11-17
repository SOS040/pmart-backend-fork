package com.pmart.cart.domain;

import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** 장바구니 규칙(Commerce 7장). 수량은 절대값, 충돌은 cartVersion, 담기는 재고 확보가 아니다. */
public final class CartRules {

  public static final String MEMBER = "MEMBER";
  public static final String GUEST = "GUEST";

  public static final String MERGE_CONFIRMED = "MERGE_CONFIRMED";
  public static final String MERGE_PARTIAL = "MERGE_PARTIAL";
  public static final String MERGE_REJECTED = "MERGE_REJECTED";

  /** 장바구니 주인(토큰 주체). 회원 = accountId, 비회원 = 비회원 토큰 sub. */
  public record Owner(String type, String id) {}

  /**
   * 최소 주문 금액(BR-14 확정 정책 15,000원 — order-service {@code OrderRules.MIN_ORDER_AMOUNT} 와 같은 값, 정책이
   * 바뀌면 둘 다 고친다). 할인·쿠폰 전 상품액 기준, 배달팁·봉투값 제외. 장바구니는 표시 안내만 하고 실제 차단은 주문 초안 견적이 한다.
   */
  public static final long MIN_ORDER_AMOUNT = 15_000;

  private CartRules() {}

  /**
   * 최소 주문까지 더 담을 금액(0.2.0) = max(0, 최소 − 선택 품목 금액). 선택 품목 금액을 모르면(가격 불명) null — 0 으로 보지 않는다. order
   * 의 {@code Quote.minOrderShortfall} 과 같은 산식.
   */
  public static @Nullable Long minOrderShortfall(long minOrderAmount, @Nullable Long itemAmount) {
    if (itemAmount == null) {
      return null;
    }
    return Math.max(0, minOrderAmount - itemAmount);
  }

  /** 표시용 가용(계약 CartItemDisplay.availability). availableQty 는 LOW 일 때만(살 수 있는 최대 수량 안내). */
  public record Availability(String state, @Nullable Integer exposedQty) {
    public static final Availability UNKNOWN = new Availability("UNKNOWN", null);
  }

  /**
   * 담당 MFC 가용(A = E − R)을 장바구니 표시로(Commerce 6.11·7장). 담기·구매 보장이 아니다.
   *
   * <ul>
   *   <li>취급 OFF → NOT_CARRIED. 수량 불명 → UNKNOWN(0 아님).
   *   <li>A ≤ 0 → SOLD_OUT, 0 < A < 담은 수량 → LOW(부족, 수량 공개), 그 밖 AVAILABLE.
   *   <li>"N개 남음" 같은 잔여 수량 노출 임계값은 운영 설정이라(Commerce 6.11) 정하지 않는다 — 충분할 때는 수량을 공개하지 않는다.
   * </ul>
   */
  public static Availability availability(
      @Nullable Integer available, boolean handlingEnabled, int quantity) {
    if (!handlingEnabled) {
      return new Availability("NOT_CARRIED", null);
    }
    if (available == null) {
      return Availability.UNKNOWN;
    }
    if (available <= 0) {
      return new Availability("SOLD_OUT", 0);
    }
    if (available < quantity) {
      return new Availability("LOW", available);
    }
    return new Availability("AVAILABLE", null);
  }

  /** 마지막 변경 후 보존 기간(Commerce [채택 설계] 30일)이 지났는가. */
  public static boolean expired(Instant lastChangedAt, Duration retention, Instant now) {
    return !lastChangedAt.plus(retention).isAfter(now);
  }

  /**
   * 본문 expectedVersion 과 헤더 X-Expected-Version 이 둘 다 있으면 같아야 한다(계약). 본문 값이 정본.
   *
   * @throws PmartException 400
   */
  public static int expectedVersion(@Nullable Integer body, @Nullable Integer header) {
    if (body == null) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "expectedVersion 이 필요합니다.");
    }
    if (header != null && !header.equals(body)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "본문 expectedVersion 과 X-Expected-Version 이 다릅니다.");
    }
    return body;
  }

  /** 병합 결과 상태: 모두 이관 → CONFIRMED, 일부 → PARTIAL, 하나도 못 함 → REJECTED. */
  public static String mergeState(int merged, int requested) {
    if (merged == 0) {
      return MERGE_REJECTED;
    }
    return merged == requested ? MERGE_CONFIRMED : MERGE_PARTIAL;
  }

  // ---------- 오류 ----------

  public static PmartException forbiddenOwner() {
    return new PmartException(
        "FORBIDDEN_OWNER", HttpStatus.FORBIDDEN, "현재 고객·기기의 장바구니가 아닙니다.", false, List.of());
  }

  public static PmartException versionConflict(int current) {
    return new PmartException(
        "CART_VERSION_CONFLICT",
        HttpStatus.CONFLICT,
        "장바구니가 그 사이 바뀌었습니다(현재 cartVersion " + current + "). 다시 조회한 뒤 확인하세요.",
        true,
        List.of());
  }

  public static PmartException notSaleable(String sku, String why) {
    return new PmartException(
        "SKU_NOT_SALEABLE",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "지금 담을 수 없는 상품입니다(" + sku + ": " + why + ").",
        false,
        List.of(new PmartException.Detail("sku", why)));
  }

  public static PmartException limitExceeded(String baseProductId, int totalUnits, int limitUnits) {
    return new PmartException(
        "QUANTITY_LIMIT_EXCEEDED",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "같은 상품은 최대 " + limitUnits + "개까지 담을 수 있습니다(지금 " + totalUnits + "개).",
        false,
        List.of(
            new PmartException.Detail(
                "baseProductId:" + baseProductId, totalUnits + ">" + limitUnits)));
  }

  /** 주문 확정 후 남길 수량. 주문한 만큼만 빼고(그 뒤 더 담았으면 남는다), 0 이하면 품목 삭제. */
  public static int remainingAfterOrder(int cartQuantity, int orderedQuantity) {
    return Math.max(0, cartQuantity - Math.max(0, orderedQuantity));
  }
}
