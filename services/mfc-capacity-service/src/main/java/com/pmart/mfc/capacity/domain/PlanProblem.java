package com.pmart.mfc.capacity.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 배정 추천(WK-09) 입력·결과. 시간 단위는 계산 시작(0)부터의 분. 계산 엔진(OR-Tools)과 무관한 순수 값.
 *
 * @param horizon 계획 구간(분)
 * @param packingStations 동시 PACK 상한(패킹대 수)
 */
public record PlanProblem(
    int horizon, int packingStations, List<Task> tasks, List<Worker> workers, Weights weights) {

  /** 목적 가중치(임시 구현값). */
  public record Weights(long unassigned, long late, long maxLoad) {
    public static final Weights DEFAULT = new Weights(W_UNASSIGNED, W_LATE, W_MAX_LOAD);
  }

  public PlanProblem(int horizon, int packingStations, List<Task> tasks, List<Worker> workers) {
    this(horizon, packingStations, tasks, workers, Weights.DEFAULT);
  }

  /**
   * @param due 약속 준비 시각(분, 음수면 이미 지남). 없으면 null — 지연을 세지 않는다
   * @param priority 정해진 순서의 앞섬(클수록 먼저 넣는다, 2026-11-08 사용자 결정: 주문 작업 3 → 자동 보충·긴급 이동 2 → 감독자 이동 1 →
   *     취소품 복귀 0). 미배정 벌점에 조금 더한다 — 미배정 개수를 줄이는 것이 먼저, 같은 개수면 앞선 유형을 넣는다
   */
  public record Task(
      String taskId,
      String taskType,
      int version,
      int minutes,
      @Nullable Integer due,
      int priority) {
    public Task(String taskId, String taskType, int version, int minutes, @Nullable Integer due) {
      this(taskId, taskType, version, minutes, due, 0);
    }
  }

  /**
   * @param busy 이미 막힌 구간 [start, end) — 앞서 맡은 작업·근무 밖
   */
  public record Worker(String workerId, int dutyVersion, Set<String> taskTypes, List<int[]> busy) {
    public boolean can(String taskType) {
      return taskTypes.contains(taskType);
    }
  }

  public record Item(String taskId, String workerId, int start, int end, int late) {}

  /**
   * @param status OPTIMAL·FEASIBLE·INFEASIBLE·UNKNOWN·MODEL_INVALID·NOT_RUN(풀 작업이 없음)
   * @param unassigned 엔진이 배정하지 못한 작업 ID(시간 부족)
   */
  public record Result(
      String status,
      List<Item> items,
      List<String> unassigned,
      int lateMinutes,
      int maxLoadMinutes,
      long objective,
      long wallMs) {
    public boolean solved() {
      return "OPTIMAL".equals(status) || "FEASIBLE".equals(status) || "NOT_RUN".equals(status);
    }
  }

  /** 목적 가중치(Q-08 전 임시): 미배정 → 지연 분 → 최대 부하 → 시작 시각 합. */
  public static final long W_UNASSIGNED = 1_000_000L;

  public static final long W_LATE = 1_000L;
  public static final long W_MAX_LOAD = 10L;

  /**
   * 근무 구간 → 막힌 구간(계획 구간 [0, horizon) 에서 근무 밖). 근무가 없으면(근무를 안 쓰는 작업자) 막힌 구간 없음.
   *
   * @param shifts [start, end) 분, 계획 구간 밖이어도 된다
   */
  public static List<int[]> offShift(List<int[]> shifts, int horizon) {
    List<int[]> out = new ArrayList<>();
    if (shifts.isEmpty()) {
      return out;
    }
    List<int[]> sorted =
        shifts.stream()
            .map(s -> new int[] {Math.max(0, s[0]), Math.min(horizon, s[1])})
            .filter(s -> s[1] > s[0])
            .sorted(Comparator.comparingInt((int[] s) -> s[0]))
            .toList();
    int cur = 0;
    for (int[] s : sorted) {
      if (s[0] > cur) {
        out.add(new int[] {cur, s[0]});
      }
      cur = Math.max(cur, s[1]);
    }
    if (cur < horizon) {
      out.add(new int[] {cur, horizon});
    }
    return out;
  }
}
