package com.pmart.order.orchestration.domain;

import java.util.Set;

/** S4 예약 시간 변경 사가 단계(2026-11-16 — OMS §4 "실패하거나 준비 시작이 먼저 확정되면 새 임시 슬롯만 해제하고 기존 시간·예약·금액을 유지"). */
public final class TimeChangeRules {

  public static final String FIND_RESOURCES = "FIND_RESOURCES";
  public static final String HOLD_SLOT = "HOLD_SLOT";
  public static final String HOLD_CAPACITY = "HOLD_CAPACITY";
  public static final String MOVE_LOT_DATE = "MOVE_LOT_DATE";
  public static final String RESCHEDULE_WORK = "RESCHEDULE_WORK";
  public static final String CONFIRM_SLOT = "CONFIRM_SLOT";
  public static final String CONFIRM_CAPACITY = "CONFIRM_CAPACITY";
  public static final String RELEASE_NEW = "RELEASE_NEW";
  public static final String REPORT_ORDER = "REPORT_ORDER";

  /** 이 단계 전에 거절되면 기존 약속을 지키고 새 것만 되돌린다(확정 지점 = RESCHEDULE_WORK 성공). */
  public static final Set<String> BEFORE_COMMIT =
      Set.of(FIND_RESOURCES, HOLD_SLOT, HOLD_CAPACITY, MOVE_LOT_DATE, RESCHEDULE_WORK);

  private TimeChangeRules() {}

  public static String requestId(String sagaId, String step) {
    return sagaId + ":" + step;
  }
}
