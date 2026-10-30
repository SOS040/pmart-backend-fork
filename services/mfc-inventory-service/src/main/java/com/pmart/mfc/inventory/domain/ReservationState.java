package com.pmart.mfc.inventory.domain;

/**
 * 예약 상태(계약 ReservationPartState 의 슬라이스 4 부분). HELD → CONFIRMED / RELEASED / EXPIRED, CONFIRMED →
 * RELEASED. BLOCKED·CONSUMED 는 품질·인계 구현 때 추가한다.
 */
public enum ReservationState {
  HELD,
  CONFIRMED,
  RELEASED,
  EXPIRED;

  /** 수량(R)을 점유 중인 상태. */
  public boolean holdsStock() {
    return this == HELD || this == CONFIRMED;
  }

  /** 확정 판정. */
  public Transition confirm() {
    return switch (this) {
      case HELD -> Transition.APPLY;
      case CONFIRMED -> Transition.ALREADY;
      case RELEASED, EXPIRED -> Transition.INVALID;
    };
  }

  /** 해제 판정. 이미 끝난 예약 해제는 변화 없는 성공(보상 재시도 안전). */
  public Transition release() {
    return holdsStock() ? Transition.APPLY : Transition.ALREADY;
  }

  /** 전이 판정 결과. */
  public enum Transition {
    /** 바꾼다. */
    APPLY,
    /** 이미 목표 상태(또는 더 진행됨) — 변화 없이 성공. */
    ALREADY,
    /** 바꿀 수 없음 — INVALID_STATE. */
    INVALID
  }
}
