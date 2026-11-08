package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import org.junit.jupiter.api.Test;

/** 승인 번호 관문(외부 검토 #8): 개발 환경이 RECORD_ONLY 를 명시할 때만 확인 없는 기록을 허용, 아니면 CONFIG_REQUIRED. */
class ApprovalRefsTest {

  @Test
  void onlyExplicitRecordOnlyModeIsUsable() {
    assertThatCode(() -> new ApprovalRefs("RECORD_ONLY").requireUsable())
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new ApprovalRefs("").requireUsable())
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("pmart.approval-ref.mode");
    assertThatThrownBy(() -> new ApprovalRefs("VERIFY").requireUsable())
        .isInstanceOf(PmartException.class);
  }
}
