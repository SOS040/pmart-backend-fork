package com.pmart.mfc.capacity.application;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.capacity.plan.*} — 배정 추천(WK-09) 정책값. 미설정은 0·무제한으로 대체하지 않는다.
 *
 * @param mfcFulfillmentBaseUrl 작업 목록(READY·ASSIGNED·IN_PROGRESS) 동기 조회 주소
 * @param taskMinutes 작업 유형 → 예상 분(설계 가정 PICK 7·PACK 4 는 승인 전 LAN 시험값). 없는 유형의 작업은 미배정
 *     CONFIG_REQUIRED, 비면 계산 거절
 * @param timeLimit 계산 시간 제한(게이트웨이 10초보다 짧게). 없으면 계산 거절
 * @param packingStations 동시 PACK 상한. 없으면 계산 거절
 */
@ConfigurationProperties(prefix = "pmart.capacity.plan")
public record PlanProperties(
    @Nullable String mfcFulfillmentBaseUrl,
    @Nullable Map<String, Integer> taskMinutes,
    @Nullable Duration timeLimit,
    @Nullable Integer packingStations,
    @Nullable Map<String, Long> weights,
    @Nullable String mfcInboundBaseUrl,
    @Nullable String centerMasterBaseUrl,
    @Nullable String mfcLocationBaseUrl,
    @Nullable String pickRouteStart,
    @Nullable Travel travel,
    @Nullable Long inputMaxBytes) {

  /**
   * 작업 시간 추정(0.11.0, 임시 구현값 — 시뮬레이터 값): 걷는 속도(m/s), 줄당 스캔 초, 개수당 처리 초, 포장 위치(피킹 끝). 하나라도 없으면 추정 없이
   * 유형 분.
   */
  public record Travel(
      @Nullable Double walkSpeedMps,
      @Nullable Integer scanSecPerLine,
      @Nullable Integer secPerUnit,
      @Nullable String packLocation) {}

  public static final String TASK_MINUTES_KEY = "pmart.capacity.plan.task-minutes";
  public static final String TIME_LIMIT_KEY = "pmart.capacity.plan.time-limit";
  public static final String PACKING_STATIONS_KEY = "pmart.capacity.plan.packing-stations";

  /**
   * 목적 가중치(임시 구현값, Q-08 전) — 설정 {@code weights.UNASSIGNED·LATE·MAX_LOAD} 가 없으면 기본값. 필수 조건은 가중치가 아니라
   * 제약이다.
   */
  public PlanProblemWeights resolvedWeights() {
    Map<String, Long> w = weights == null ? Map.of() : weights;
    return new PlanProblemWeights(
        w.getOrDefault("UNASSIGNED", 1_000_000L),
        w.getOrDefault("LATE", 1_000L),
        w.getOrDefault("MAX_LOAD", 10L));
  }

  public record PlanProblemWeights(long unassigned, long late, long maxLoad) {}
}
