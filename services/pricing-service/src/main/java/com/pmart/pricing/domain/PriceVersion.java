package com.pmart.pricing.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * SKU 기본 판매가격의 한 버전. 버전 번호와 효력 시각은 함께 늘어난다({@link PriceRules#scheduleConflict}). 상태는 발효 사건 발행 여부다.
 */
public record PriceVersion(
    String sku,
    int version,
    long basePrice,
    @Nullable Long previousPrice,
    Instant effectiveFrom,
    @Nullable Instant effectiveTo,
    State state,
    String approvalId) {

  public enum State {
    SCHEDULED,
    EFFECTIVE,
    SUPERSEDED
  }
}
