package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import org.junit.jupiter.api.Test;

/** 배정 추천 0.9.4 — 계산 상태 세분(원문 WK-09)과 진행 중 작업의 남은 시간. */
class PlanRulesTest {

  @Test
  void solverStatusMapsToPlanState() {
    assertThat(PlanService.planState(true, "OPTIMAL")).isEqualTo("PROPOSED");
    assertThat(PlanService.planState(true, "FEASIBLE")).isEqualTo("PROPOSED");
    // 시간 안에 못 찾음은 불가능 증명이 아니다
    assertThat(PlanService.planState(false, "UNKNOWN")).isEqualTo("UNDETERMINED");
    assertThat(PlanService.planState(false, "MODEL_INVALID")).isEqualTo("INVALID");
    assertThat(PlanService.planState(false, "INFEASIBLE")).isEqualTo("NO_SOLUTION");
  }

  @Test
  void inProgressTaskBlocksOnlyItsRemainingShare() {
    TaskView half = new TaskView("T1", "PICK", "IN_PROGRESS", 3, "w", null, 10, 5);
    assertThat(PlanService.remainingMinutes(half, 7)).isEqualTo(4); // 7 × 5/10 = 3.5 → 올림 4
    TaskView almost = new TaskView("T2", "PICK", "IN_PROGRESS", 3, "w", null, 10, 0);
    assertThat(PlanService.remainingMinutes(almost, 7)).isEqualTo(1); // 최소 1분
    // 시작 전(ASSIGNED)·진척을 모르면 전체
    TaskView assigned = new TaskView("T3", "PICK", "ASSIGNED", 1, "w", null, 10, 10);
    assertThat(PlanService.remainingMinutes(assigned, 7)).isEqualTo(7);
    TaskView unknown = new TaskView("T4", "MOVE", "IN_PROGRESS", 0, "w", null);
    assertThat(PlanService.remainingMinutes(unknown, 5)).isEqualTo(5);
  }
}
