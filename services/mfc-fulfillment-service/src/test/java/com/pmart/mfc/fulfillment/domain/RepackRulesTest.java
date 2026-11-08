package com.pmart.mfc.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepackRulesTest {

  @Test
  void onlySealedPackageWithoutHandoverOrStopCanBeRepacked() {
    assertThat(RepackRules.rejection("SEALED", false, false, false, false)).isNull();
    assertThat(RepackRules.rejection("HANDED_OVER", false, false, true, false))
        .isEqualTo(HandoverRules.ALREADY_HANDED_OVER);
    assertThat(RepackRules.rejection("INVALIDATED", false, false, false, false))
        .isEqualTo(HandoverRules.INVALID_STATE);
    assertThat(RepackRules.rejection("SEALED", true, true, false, false))
        .isEqualTo(HandoverRules.FENCE_ACTIVE);
    assertThat(RepackRules.rejection("SEALED", true, true, true, false))
        .isEqualTo(HandoverRules.HANDOVER_RESULT_PENDING);
    assertThat(RepackRules.rejection("SEALED", false, false, false, true))
        .isEqualTo(HandoverRules.INVALID_STATE);
    // 허가 만료 뒤 실제로 안 넘겼다는 확인 전(D-1 미결)에는 재포장하지 않는다
    assertThat(RepackRules.rejection("SEALED", false, true, false, false))
        .isEqualTo(RepackRules.FENCE_EXPIRED_UNCONFIRMED);
  }

  @Test
  void contentsMustStayTheSame() {
    List<Content> now =
        List.of(new Content("L1", "E-1", "LOT-A", 1), new Content("L1", "E-1", "LOT-B", 1));
    assertThat(RepackRules.contentMismatch(now, null)).isNull();
    assertThat(RepackRules.contentMismatch(now, List.of(new ContentRequest("L1", "E-1", null, 2))))
        .isNull();
    assertThat(
            RepackRules.contentMismatch(
                now,
                List.of(
                    new ContentRequest("L1", "E-1", "LOT-A", 1),
                    new ContentRequest("L1", "E-1", "LOT-B", 1))))
        .isNull();
    assertThat(RepackRules.contentMismatch(now, List.of(new ContentRequest("L1", "E-1", null, 1))))
        .isNotNull();
  }
}
