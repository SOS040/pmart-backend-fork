package com.pmart.customer.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 주소의 권역 판정 사본(delivery-serviceability 정본). 고객에게는 3상태로만 보인다.
 *
 * <ul>
 *   <li>SERVICEABLE: 담당 MFC 1곳 확정(mfcId 필수 — 없으면 UNKNOWN).
 *   <li>OUT_OF_SERVICE_AREA: 권역 밖.
 *   <li>UNKNOWN: 중첩(OVERLAP)·입력 보완(NEEDS_INPUT)·조회 불명·발효 버전 없음. 임의 MFC 배정 금지, 신규 구매 보류.
 * </ul>
 *
 * @param result 판정 원결과 또는 판정하지 못한 사유 코드(운영 조사용)
 */
public record Serviceability(
    String state,
    @Nullable String result,
    @Nullable String mfcId,
    @Nullable String zoneId,
    @Nullable Integer mappingVersion,
    @Nullable String checkId,
    Instant asOf) {

  public static final String SERVICEABLE = "SERVICEABLE";
  public static final String OUT_OF_SERVICE_AREA = "OUT_OF_SERVICE_AREA";
  public static final String UNKNOWN = "UNKNOWN";

  /** 판정 결과(ServiceabilityResult) → 고객 표시 상태. */
  public static Serviceability fromCheck(
      String result,
      @Nullable String mfcId,
      @Nullable String zoneId,
      @Nullable Integer mappingVersion,
      @Nullable String checkId,
      Instant asOf) {
    String state =
        switch (result) {
          case "SERVICEABLE" -> mfcId == null || mfcId.isBlank() ? UNKNOWN : SERVICEABLE;
          case "OUT_OF_AREA" -> OUT_OF_SERVICE_AREA;
          default -> UNKNOWN;
        };
    boolean keepMfc = SERVICEABLE.equals(state);
    return new Serviceability(
        state,
        result,
        keepMfc ? mfcId : null,
        keepMfc ? zoneId : null,
        mappingVersion,
        checkId,
        asOf);
  }

  /** 판정하지 못함(조회 실패·발효 버전 없음 등). 주소는 저장하되 구매 가능성은 확인 중. */
  public static Serviceability unknown(String reason, Instant asOf) {
    return new Serviceability(UNKNOWN, reason, null, null, null, null, asOf);
  }

  public boolean serviceable() {
    return SERVICEABLE.equals(state);
  }
}
