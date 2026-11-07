package com.pmart.mfc.capacity.domain;

import com.pmart.mfc.capacity.domain.CapacityRules.HourWindow;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 운영 제한(BO-08). 범위 = 슬롯 창이 [from, to) 와 겹치는 것(to 없음 = 끝 없음). 시간 경과로 자동 해제하지 않는다. 여러 제한은 모두 적용 —
 * INTAKE_STOP 하나라도 걸리면 신규 확보 거절, CAPACITY_REDUCE 는 가장 작은 값.
 */
public final class RestrictionRules {

  public static final String OPERATION_RESTRICTED = "OPERATION_RESTRICTED";

  private RestrictionRules() {}

  /** 활성 제한 한 건. */
  public record Active(
      String restrictionId,
      String type,
      Instant effectiveFrom,
      @Nullable Instant effectiveTo,
      @Nullable Integer reducedCapacityUnits) {

    public boolean covers(HourWindow w) {
      return w.end().isAfter(effectiveFrom)
          && (effectiveTo == null || w.start().isBefore(effectiveTo));
    }
  }

  /** 이 창의 신규 확보를 막는 INTAKE_STOP 이 있는가. */
  public static @Nullable String intakeStop(List<Active> active, HourWindow w) {
    return active.stream()
        .filter(a -> "INTAKE_STOP".equals(a.type()) && a.covers(w))
        .map(Active::restrictionId)
        .findFirst()
        .orElse(null);
  }

  /** 이 창의 수용 단위 = min(설정값, 걸린 축소값들). */
  public static int capacity(int configured, List<Active> active, HourWindow w) {
    int c = configured;
    for (Active a : active) {
      if ("CAPACITY_REDUCE".equals(a.type()) && a.covers(w) && a.reducedCapacityUnits() != null) {
        c = Math.min(c, a.reducedCapacityUnits());
      }
    }
    return c;
  }

  public static List<String> applying(List<Active> active, HourWindow w) {
    return active.stream().filter(a -> a.covers(w)).map(Active::restrictionId).toList();
  }

  /**
   * 적용 요청 검사.
   *
   * @return 위반 사유, 없으면 null
   */
  public static @Nullable String invalid(
      String type, Instant from, @Nullable Instant to, @Nullable Integer reducedUnits) {
    if (!"INTAKE_STOP".equals(type) && !"CAPACITY_REDUCE".equals(type)) {
      return "restrictionType 은 INTAKE_STOP/CAPACITY_REDUCE 입니다.";
    }
    if (to != null && !to.isAfter(from)) {
      return "effectiveTo 는 effectiveFrom 뒤여야 합니다.";
    }
    if ("CAPACITY_REDUCE".equals(type) && (reducedUnits == null || reducedUnits < 0)) {
      return "CAPACITY_REDUCE 는 reducedCapacityUnits(0 이상)가 필요합니다.";
    }
    if ("INTAKE_STOP".equals(type) && reducedUnits != null) {
      return "INTAKE_STOP 에는 reducedCapacityUnits 를 두지 않습니다.";
    }
    return null;
  }
}
