package com.pmart.mfc.inventory.domain;

/**
 * 예약(조각) 상태 — 계약 ReservationPartState, MFC 문서 §3 §6 허용 전이. HELD → CONFIRMED / RELEASED / EXPIRED /
 * BLOCKED, CONFIRMED → CONSUMED / RELEASED / BLOCKED. BLOCKED(격리로 적격 상실 — 이행부족 D)·CONSUMED(인계로 소진)로
 * 가는 전이는 품질·인계 구현 때 쓴다(ADR-0040 결과 5).
 */
public enum ReservationState {
  HELD,
  CONFIRMED,
  RELEASED,
  EXPIRED,
  BLOCKED,
  CONSUMED;

  /** 현물 R 을 점유 중인 상태(HELD + CONFIRMED). BLOCKED 는 R 이 아니라 D 다. */
  public boolean holdsStock() {
    return this == HELD || this == CONFIRMED;
  }

  /** 확정 판정. */
  public Transition confirm() {
    return switch (this) {
      case HELD -> Transition.APPLY;
      case CONFIRMED, CONSUMED -> Transition.ALREADY;
      case RELEASED, EXPIRED, BLOCKED -> Transition.INVALID;
    };
  }

  /** 해제 판정. 이미 끝난 예약 해제는 변화 없는 성공(보상 재시도 안전). BLOCKED 해제(유효 취소)는 품질 구현 때. */
  public Transition release() {
    return switch (this) {
      case HELD, CONFIRMED -> Transition.APPLY;
      case RELEASED, EXPIRED -> Transition.ALREADY;
      case BLOCKED, CONSUMED -> Transition.INVALID;
    };
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
