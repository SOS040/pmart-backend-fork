package com.pmart.pricing.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 기본 판매가격 규칙(BO-04, service-map.md pricing-service).
 *
 * <ul>
 *   <li>시각 t 의 가격 = 효력 시각이 t 이하인 가장 큰 버전. 버전이 없으면 PRICE_NOT_SET(0원이 아니라 판매 불가).
 *   <li>소급 없음: 요청 효력 시각이 이미 지났으면 반영 시각부터 적용한다.
 *   <li>기간 겹침: 이미 예약된 버전의 효력 시각보다 이르거나 같은 새 버전은 거절한다(PRICE_SCHEDULE_CONFLICT). 예약을 바꾸려면 새 승인으로 뒤에
 *       둔다.
 *   <li>0원·음수 가격은 받지 않는다. 0원 허용 여부는 미정이라 임의로 채택하지 않는다(ZERO_PRICE_UNDECIDED).
 * </ul>
 */
public final class PriceRules {

  public static final Pattern SKU = Pattern.compile("^[EB]-[0-9]{8}$");

  private PriceRules() {}

  /** 승인 행 반영 거절 사유. */
  public enum LineProblem {
    SKU_INVALID,
    SKU_NOT_FOUND,
    PRICE_MISSING,
    ZERO_PRICE_UNDECIDED,
    PRICE_SCHEDULE_CONFLICT,
    DUPLICATE_SKU
  }

  public static Optional<LineProblem> checkLine(
      @Nullable String sku, @Nullable Long basePrice, boolean skuKnown) {
    if (sku == null || !SKU.matcher(sku).matches()) {
      return Optional.of(LineProblem.SKU_INVALID);
    }
    if (!skuKnown) {
      return Optional.of(LineProblem.SKU_NOT_FOUND);
    }
    if (basePrice == null) {
      return Optional.of(LineProblem.PRICE_MISSING);
    }
    if (basePrice <= 0) {
      return Optional.of(LineProblem.ZERO_PRICE_UNDECIDED);
    }
    return Optional.empty();
  }

  /** 실제 효력 시각: 요청이 없거나 이미 지났으면 반영 시각(소급 없음). */
  public static Instant effectiveFrom(@Nullable Instant requested, Instant appliedAt) {
    return requested == null || requested.isBefore(appliedAt) ? appliedAt : requested;
  }

  /** 새 효력 시각이 기존 버전(예약 포함)의 효력 시각 이하이면 겹침. */
  public static boolean scheduleConflict(List<PriceVersion> existing, Instant newEffectiveFrom) {
    return existing.stream()
        .filter(v -> v.state() == PriceVersion.State.SCHEDULED)
        .anyMatch(v -> !newEffectiveFrom.isAfter(v.effectiveFrom()));
  }

  /** 시각 t 에 유효한 버전. */
  public static Optional<PriceVersion> effectiveAt(List<PriceVersion> versions, Instant at) {
    return versions.stream()
        .filter(v -> !v.effectiveFrom().isAfter(at))
        .max(Comparator.comparingInt(PriceVersion::version));
  }

  /** 시각 t 뒤에 예약된 가장 가까운 버전. */
  public static Optional<PriceVersion> nextAfter(List<PriceVersion> versions, Instant at) {
    return versions.stream()
        .filter(v -> v.effectiveFrom().isAfter(at))
        .min(Comparator.comparingInt(PriceVersion::version));
  }

  /** 품목 금액 = 단가 × 수량. 넘치면 예외(계산을 조용히 틀리게 하지 않는다). */
  /**
   * 수량 행사 무료 수량(ADR-0090 A2) — 유상 수량의 buy 배수마다 free 개. promotion-service
   * PromotionRules.freeQuantity 와 같은 식.
   */
  public static int freeQuantity(int buy, int free, int paidQuantity) {
    return buy <= 0 || free <= 0 || paidQuantity <= 0 ? 0 : (paidQuantity / buy) * free;
  }

  public static long lineAmount(long unitPrice, int quantity) {
    return Math.multiplyExact(unitPrice, (long) quantity);
  }

  /**
   * 장바구니 쿠폰 안분(ADR-0082 — 부록 C §8.8): 대상 금액에 비례, 원 미만 버림, 남은 원은 대상 금액이 가장 큰 품목(같으면 앞 품목)에. 품목 몫은 그
   * 품목 금액을 넘지 않는다(total ≤ 합이면 성립).
   */
  public static long[] allocate(long total, long[] bases) {
    long[] out = new long[bases.length];
    long sum = 0;
    for (long b : bases) {
      sum = Math.addExact(sum, b);
    }
    if (total <= 0 || sum <= 0) {
      return out;
    }
    long t = Math.min(total, sum);
    long given = 0;
    int largest = 0;
    for (int i = 0; i < bases.length; i++) {
      out[i] = Math.multiplyExact(t, bases[i]) / sum;
      given += out[i];
      if (bases[i] > bases[largest]) {
        largest = i;
      }
    }
    out[largest] += t - given;
    return out;
  }
}
