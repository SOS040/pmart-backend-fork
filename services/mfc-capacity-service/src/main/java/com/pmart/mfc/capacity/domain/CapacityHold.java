package com.pmart.mfc.capacity.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 작업 여력 Hold. HELD→CONFIRMED/RELEASED/EXPIRED, CONFIRMED→RELEASED. RELEASED·EXPIRED 는 종료(수용 단위 반환).
 *
 * <p>만료 시각이 지난 HELD 는 정리 작업 전이라도 점유로 세지 않고, 확정할 수 없다({@link #effectiveState(Instant)}).
 */
public record CapacityHold(
    String holdId,
    String orderId,
    String mfcId,
    CapacityRules.HourWindow window,
    int units,
    State state,
    int version,
    Instant expiresAt,
    @Nullable String releaseReason) {

  public enum State {
    HELD,
    CONFIRMED,
    RELEASED,
    EXPIRED;

    public boolean active() {
      return this == HELD || this == CONFIRMED;
    }
  }

  /** 시각을 반영한 상태: 만료 시각이 지난 HELD 는 EXPIRED. */
  public State effectiveState(Instant now) {
    return state == State.HELD && !now.isBefore(expiresAt) ? State.EXPIRED : state;
  }
}
