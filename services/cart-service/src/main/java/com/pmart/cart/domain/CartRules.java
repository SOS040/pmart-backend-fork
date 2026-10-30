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

  private CartRules() {}

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
