package com.pmart.employeeauth.application;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * "이미 유효하거나 시작 전인 같은 권한" 판정(2026-11-17 HR-2 — permissions.md §9-11). hr-service 가 요청을 받을 때(409
 * GRANT_ALREADY_ACTIVE) 같은 규칙으로 먼저 거르고, 이 서비스는 승인 반영({@link GrantApprovalHandler})의 안전망으로 쓴다.
 *
 * <ul>
 *   <li>역할이 같다.
 *   <li>센터가 겹친다 — 센터 지정 역할(role-info center-scoped)은 센터가 하나라도 같거나 둘 다 센터 없음, 센터 지정이 아닌 역할은 센터와 상관없이
 *       겹친다(역할 자체가 전체 범위).
 *   <li>기간이 <b>지금 이후로</b> 겹친다 — {@code [max(시작, 기존 시작, 지금), min(끝, 기존 끝))} 이 비어 있지 않다(끝 null = 기한
 *       없음). 이미 지난 부분(회수·만료된 grant, 지난 시작일)은 보지 않는다. 기존 권한이 끝나는 시각부터 시작하는 권한은 겹치지 않는다.
 * </ul>
 */
public final class GrantOverlap {

  private GrantOverlap() {}

  /** 기간 [from, until) — until null = 기한 없음. */
  public record Period(Instant from, @Nullable Instant until) {}

  public static boolean centersOverlap(
      boolean centerScoped, Collection<String> a, Collection<String> b) {
    if (!centerScoped) {
      return true;
    }
    if (a.isEmpty() && b.isEmpty()) {
      return true;
    }
    Set<String> s = new HashSet<>(a);
    return b.stream().anyMatch(s::contains);
  }

  /** 두 기간이 {@code now} 이후로 겹치는가. */
  public static boolean periodsOverlap(Period a, Period b, Instant now) {
    Instant lo = max(max(a.from(), b.from()), now);
    Instant hi = min(a.until(), b.until());
    return hi == null || lo.isBefore(hi);
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static @Nullable Instant min(@Nullable Instant a, @Nullable Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }
}
