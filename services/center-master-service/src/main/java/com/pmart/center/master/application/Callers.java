package com.pmart.center.master.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.security.PmartPrincipal.PrincipalType;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.Set;

/**
 * 공개 경로 호출자 확인. 계약 x-pmart-user-types 를 서비스 안에서도 본다 — 게이트웨이 허용 목록이 아직 RECORD(기록만)라 고객 토큰이 직원 조회에
 * 닿았다(권한 빈틈 정리 2026-11-17, docs/architecture/auth/permissions.md §9).
 */
public final class Callers {

  private Callers() {}

  /** 센터·캘린더 조회 — 직원만. */
  public static void requireOperator() {
    require(Set.of(PrincipalType.OPERATOR), "직원만 볼 수 있습니다.");
  }

  /** MFC 픽업 지점 — 라이더·직원. */
  public static void requireRiderOrOperator() {
    require(Set.of(PrincipalType.RIDER, PrincipalType.OPERATOR), "라이더·직원만 볼 수 있습니다.");
  }

  private static void require(Set<PrincipalType> allowed, String message) {
    PmartPrincipal.current()
        .filter(p -> allowed.contains(p.type()))
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, message));
  }
}
