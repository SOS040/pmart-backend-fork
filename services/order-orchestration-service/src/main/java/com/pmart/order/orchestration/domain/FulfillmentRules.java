package com.pmart.order.orchestration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** S2 이행 요청 규칙(슬라이스 6a, docs/business/flows/order-checkout-saga.md §2.0 D6-4·D6-5). 순수 계산만. */
public final class FulfillmentRules {

  public static final String REQUEST_FULFILLMENT = "REQUEST_FULFILLMENT";
  public static final String AWAIT_RECOVERY = "AWAIT_RECOVERY";
  public static final String REQUEST_SYSTEM_CANCEL = "REQUEST_SYSTEM_CANCEL";

  /** 재확인 간격(D6-5 "1분마다" — 운영 설정이 아닌 고정값). */
  public static final Duration RECHECK_INTERVAL = Duration.ofMinutes(1);

  /** 같은 MFC 재시도 대상 거절 사유(D6-5). 여력 부족은 결제 때 이미 점유했으므로 실제 문제로 보고 바로 자동 취소. */
  public static final Set<String> RETRYABLE = Set.of("INVENTORY_MISMATCH", "OPERATION_RESTRICTED");

  private FulfillmentRules() {}

  /** 거절 뒤(또는 대기 중 재확인 때) 다음 행동. */
  public enum AfterReject {
    /** 재시도 대기(1분 뒤 재확인). */
    WAIT,
    /** 센터 재시도 설정(W·최대 횟수)이 없다 — 그 주문만 보류(자동 취소하지 않음). */
    CONFIG_REQUIRED,
    /** 자동 전체 취소(D6-4). */
    CANCEL
  }

  public static String requestId(String sagaId, String step) {
    return sagaId + ":" + step;
  }

  public static String attemptRequestId(String sagaId, int workVersion) {
    return sagaId + ":" + REQUEST_FULFILLMENT + ":v" + workVersion;
  }

  /**
   * 거절 뒤 판단. 순서: 재시도 대상 아님 → 취소. 배송 슬롯 시작이 지났다 → 취소(기다리지 않음). 센터 설정 없음 → 보류. 새 작업 버전을 다 썼다 → 취소. 처음
   * 거절부터 W분이 지났다 → 취소(대기는 처음 거절부터 한 번만). 그 밖 → 대기.
   *
   * @param workVersion 지금까지 보낸 마지막 작업 버전(1 = 첫 요청, 새 버전 사용 수 = workVersion − 1)
   */
  public static AfterReject afterReject(
      String rejectReason,
      Instant now,
      @Nullable Instant slotStart,
      Instant firstRejectedAt,
      @Nullable Duration window,
      @Nullable Integer maxNewVersions,
      int workVersion) {
    if (!RETRYABLE.contains(rejectReason)) {
      return AfterReject.CANCEL;
    }
    if (slotStart != null && !now.isBefore(slotStart)) {
      return AfterReject.CANCEL;
    }
    if (window == null || maxNewVersions == null) {
      return AfterReject.CONFIG_REQUIRED;
    }
    if (workVersion - 1 >= maxNewVersions) {
      return AfterReject.CANCEL;
    }
    if (!now.isBefore(firstRejectedAt.plus(window))) {
      return AfterReject.CANCEL;
    }
    return AfterReject.WAIT;
  }

  /**
   * 원래 재고 예약이 되살아났는가(D6-5 세부 "원래 예약만"): 예약이 CONFIRMED 이고 SKU 마다 CONFIRMED 조각 합 ≥ 작업 수량. 요청
   * 본문(ConfirmOrder)의 출고 행으로 필요 수량을 센다. 예약 조회 실패(null)는 되찾지 못한 것으로 본다(다음 재확인).
   */
  public static boolean reservationRecovered(@Nullable JsonNode reservation, JsonNode request) {
    if (reservation == null || !"CONFIRMED".equals(reservation.path("state").asString(""))) {
      return false;
    }
    Map<String, Integer> have = new LinkedHashMap<>();
    for (JsonNode p : reservation.path("parts")) {
      if ("CONFIRMED".equals(p.path("state").asString(""))) {
        have.merge(p.path("sku").asString(""), p.path("qty").asInt(0), Integer::sum);
      }
    }
    Map<String, Integer> need = new LinkedHashMap<>();
    for (JsonNode s : request.path("shipments")) {
      for (JsonNode l : s.path("lines")) {
        need.merge(l.path("sku").asString(""), l.path("qty").asInt(0), Integer::sum);
      }
    }
    for (Map.Entry<String, Integer> e : need.entrySet()) {
      if (have.getOrDefault(e.getKey(), 0) < e.getValue()) {
        return false;
      }
    }
    return !need.isEmpty();
  }
}
