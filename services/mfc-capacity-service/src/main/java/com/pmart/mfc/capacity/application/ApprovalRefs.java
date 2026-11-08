package com.pmart.mfc.capacity.application;

import com.pmart.common.web.PmartException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 승인 번호(approvalRef) 검증 관문(2026-11-08 외부 검토 #8). 승인 서비스가 아직 없어 번호가 실제 승인인지 확인할 수 없다 — 그래서 확인 없이 기록만
 * 하는 것은 설정 {@code pmart.approval-ref.mode: RECORD_ONLY} 를 명시한 개발 환경(LAN·local·시험)에서만 허용한다. 값이
 * 없으면(운영) 재고를 다시 팔리게 하거나 없애거나 운영을 다시 여는 승인 동작은 CONFIG_REQUIRED. 격리·접수 중지 같은 보호 동작에는 쓰지 않는다(막으면 안
 * 됨).
 */
@Component
public class ApprovalRefs {

  public static final String KEY = "pmart.approval-ref.mode (승인 번호 검증 — 승인 서비스 연동 전 운영 금지)";

  private final String mode;

  public ApprovalRefs(@Value("${pmart.approval-ref.mode:}") String mode) {
    this.mode = mode == null ? "" : mode.trim();
  }

  /** 검증 없이 기록만 하는 것이 허용된 환경인지 — 아니면 CONFIG_REQUIRED. */
  public void requireUsable() {
    if (!"RECORD_ONLY".equals(mode)) {
      throw PmartException.configRequired(KEY);
    }
  }
}
