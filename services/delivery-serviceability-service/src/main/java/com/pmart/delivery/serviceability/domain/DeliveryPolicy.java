package com.pmart.delivery.serviceability.domain;

import java.util.List;

/**
 * 배송 가능 조건(1단계 확정값만). 승인 대기 값은 상수로 채우지 않고 NOT_SET 으로 응답한다(0·무제한 아님).
 *
 * <ul>
 *   <li>방식: 한집(SINGLE)·알뜰(BUNDLED)·예약(SCHEDULED) — Commerce [사용자 재확인] 기존 배달팁 정책의 세 방식.
 *   <li>배송당 중량 경계 10,000g — Commerce [확정] 총 상품중량 10kg 초과는 같은 주문 안에서 여러 배송으로 나눈다(10,000g 은 분할 없음).
 *       포장재 포함 여부·단일 상품 10kg 초과 처리는 승인 대기.
 *   <li>시간 제한: [확정 정책] 00:00 이상 08:00 미만 실제 배송 없음.
 *   <li>부피 상한·보냉 가능 여부·운송 수단: 승인 대기 → NOT_SET·빈 목록.
 * </ul>
 */
public final class DeliveryPolicy {

  public static final List<String> MODES = List.of("SINGLE", "BUNDLED", "SCHEDULED");
  public static final int MAX_WEIGHT_PER_DELIVERY_G = 10_000;
  public static final String NO_DELIVERY_START = "00:00";
  public static final String NO_DELIVERY_END = "08:00";

  private DeliveryPolicy() {}
}
