package com.pmart.mfc.location.domain;

/**
 * 위치 상태 전이(계약 LocationState, MFC 문서 IV-03·IV-11). ACTIVE ↔ CLOSED, ACTIVE ↔ COUNT_LOCKED(실사 잠금).
 * RETIRED 는 폐지로만 가고 되돌리지 않는다. CLOSED·COUNT_LOCKED 사이는 ACTIVE 를 거친다.
 */
public final class LocationRules {

  private LocationRules() {}

  public enum State {
    ACTIVE,
    CLOSED,
    COUNT_LOCKED,
    RETIRED
  }

  public enum Transition {
    APPLY,
    ALREADY,
    INVALID
  }

  public static Transition change(State from, State to) {
    if (from == to) {
      return Transition.ALREADY;
    }
    if (from == State.RETIRED || to == State.RETIRED) {
      return Transition.INVALID;
    }
    return (from == State.ACTIVE || to == State.ACTIVE) ? Transition.APPLY : Transition.INVALID;
  }
}
