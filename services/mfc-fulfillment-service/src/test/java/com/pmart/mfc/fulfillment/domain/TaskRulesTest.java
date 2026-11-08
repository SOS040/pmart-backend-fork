package com.pmart.mfc.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.fulfillment.domain.TaskRules.ActiveAssignment;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import com.pmart.mfc.fulfillment.domain.TaskRules.PickedPart;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 시작 점유 판정(WK-03), 포장 LOT 배정(WK-07). */
class TaskRulesTest {

  static final ActiveAssignment W1 = new ActiveAssignment("w1", 2);

  @Test
  void startNeedsActiveAssigneeWithSameVersion() {
    assertThat(TaskRules.startRejection("ASSIGNED", true, W1, "w1", 2)).isNull();
    assertThat(TaskRules.startRejection("READY", true, W1, "w1", 2)).isNull(); // 투영 지연 — 동기 조회가 정본
    assertThat(TaskRules.startRejection("ASSIGNED", true, W1, "w2", 2))
        .isEqualTo(TaskRules.NOT_ASSIGNEE);
    assertThat(TaskRules.startRejection("ASSIGNED", true, null, "w1", 2))
        .isEqualTo(TaskRules.NOT_ASSIGNEE);
    assertThat(TaskRules.startRejection("ASSIGNED", true, W1, "w1", 1))
        .isEqualTo(TaskRules.VERSION_CONFLICT);
    assertThat(TaskRules.startRejection("IN_PROGRESS", true, W1, "w1", 2))
        .isEqualTo(TaskRules.ALREADY_STARTED);
    assertThat(TaskRules.startRejection("WAITING", false, W1, "w1", 2))
        .isEqualTo(TaskRules.PREDECESSOR_NOT_COMPLETED);
    // 배정 뒤 작업자가 정지·업무 변경 → 시작 거절(capacity 가 조회 때 알려 준 이유 그대로)
    assertThat(
            TaskRules.startRejection(
                "ASSIGNED", true, new ActiveAssignment("w1", 2, "WORKER_NOT_ACTIVE"), "w1", 2))
        .isEqualTo("WORKER_NOT_ACTIVE");
    assertThat(TaskRules.startRejection("CANCELLED", true, W1, "w1", 2))
        .isEqualTo(TaskRules.INVALID_STATE);
  }

  @Test
  void packingTakesPickedLotsEarliestExpiryFirst() {
    var parts =
        List.of(
            new PickedPart("L1", "E-1", "LOT-B", "2026-12-01", 2),
            new PickedPart("L1", "E-1", "LOT-A", "2026-11-20", 1),
            new PickedPart("L2", "E-2", "LOT-C", null, 1));
    var ok =
        TaskRules.allocatePacking(
            List.of(
                new ContentRequest("L1", "E-1", null, 2), new ContentRequest("L2", "E-2", null, 1)),
            parts);
    assertThat(ok.error()).isNull();
    assertThat(ok.contents())
        .containsExactly(
            new Content("L1", "E-1", "LOT-A", 1),
            new Content("L1", "E-1", "LOT-B", 1),
            new Content("L2", "E-2", "LOT-C", 1));
    assertThat(
            TaskRules.allocatePacking(List.of(new ContentRequest("L1", "E-1", null, 4)), parts)
                .error())
        .isNotNull();
    assertThat(
            TaskRules.allocatePacking(List.of(new ContentRequest("L1", "E-1", "LOT-A", 2)), parts)
                .error())
        .isNotNull();
  }
}
