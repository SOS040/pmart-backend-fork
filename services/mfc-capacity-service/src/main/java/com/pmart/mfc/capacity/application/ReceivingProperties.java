package com.pmart.mfc.capacity.application;

import java.time.LocalTime;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.capacity.receiving.*} — 오전 입고 수령창(MFC 문서 6-3 "입고 차량 매일 오전"). 값은 규모 설계 가정이라 승인 전
 * 시험값(LAN). 하나라도 없으면 조회·예약 CONFIG_REQUIRED(0·무제한 대체 금지).
 *
 * @param windowStart 수령창 시작(Asia/Seoul)
 * @param windowEnd 수령창 끝(Asia/Seoul, 시작보다 뒤)
 * @param maxLoadUnitsPerArrival 1회 입고 상한(박스 환산 단위)
 * @param totalLoadUnits 하루 창 전체 상한
 * @param bayCount 하차면 수 — 한 창에 동시에 받는 차량 수
 */
@ConfigurationProperties(prefix = "pmart.capacity.receiving")
public record ReceivingProperties(
    @Nullable LocalTime windowStart,
    @Nullable LocalTime windowEnd,
    @Nullable Integer maxLoadUnitsPerArrival,
    @Nullable Integer totalLoadUnits,
    @Nullable Integer bayCount) {

  public static final String KEY = "pmart.capacity.receiving(수령창 시작·끝·1회 상한·하루 상한·하차면 수)";

  public boolean complete() {
    return windowStart != null
        && windowEnd != null
        && windowEnd.isAfter(windowStart)
        && maxLoadUnitsPerArrival != null
        && maxLoadUnitsPerArrival > 0
        && totalLoadUnits != null
        && totalLoadUnits > 0
        && bayCount != null
        && bayCount > 0;
  }
}
