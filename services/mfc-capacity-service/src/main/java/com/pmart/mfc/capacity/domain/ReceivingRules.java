package com.pmart.mfc.capacity.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 하차면 예약 판정(오전 수령창). 순수 계산. */
public final class ReceivingRules {

  private ReceivingRules() {}

  public static final String OUTSIDE_WINDOW = "OUTSIDE_RECEIVING_WINDOW";
  public static final String PER_ARRIVAL_EXCEEDED = "PER_ARRIVAL_LIMIT_EXCEEDED";
  public static final String WINDOW_FULL = "RECEIVING_WINDOW_FULL";
  public static final String NO_BAY = "NO_UNLOADING_BAY";

  /**
   * @return 거절 코드, 괜찮으면 null
   */
  public static @Nullable String rejection(
      Instant reqStart,
      Instant reqEnd,
      Instant winStart,
      Instant winEnd,
      int loadUnits,
      int maxPerArrival,
      int reservedUnits,
      int totalUnits,
      int activeReservations,
      int bays) {
    if (!reqEnd.isAfter(reqStart) || reqStart.isBefore(winStart) || reqEnd.isAfter(winEnd)) {
      return OUTSIDE_WINDOW;
    }
    if (loadUnits > maxPerArrival) {
      return PER_ARRIVAL_EXCEEDED;
    }
    if (reservedUnits + loadUnits > totalUnits) {
      return WINDOW_FULL;
    }
    if (activeReservations >= bays) {
      return NO_BAY;
    }
    return null;
  }
}
