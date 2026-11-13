package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import org.jspecify.annotations.Nullable;

/** 운영자 범위 — 그 센터 운영자(MFC 백오피스), OPERATOR_ADMIN, 고객센터 CS_AGENT(지원센터 역할이 생기면 더한다). */
final class Access {

  private Access() {}

  static boolean operatorCanSee(PmartPrincipal p, @Nullable String mfcId) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR
        && (p.hasRole("OPERATOR_ADMIN")
            || p.hasRole("CS_AGENT")
            || (mfcId != null && p.canAccessCenter(mfcId)));
  }

  /**
   * 전달 실패 확정·반려 — 관리자만(사용자 2026-11-14 "관리자로 좁히고"): 그 센터의 {@code MFC_SUPERVISOR}, OPERATOR_ADMIN,
   * CS_AGENT. 센터 소속이어도 작업자(MFC_WORKER 등)는 안 된다.
   */
  static boolean operatorCanDecide(PmartPrincipal p, @Nullable String mfcId) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR
        && (p.hasRole("OPERATOR_ADMIN")
            || p.hasRole("CS_AGENT")
            || (mfcId != null && p.hasRole("MFC_SUPERVISOR") && p.canAccessCenter(mfcId)));
  }
}
