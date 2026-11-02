package com.pmart.order.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 주문 당시 배송지·수령 연락처 사본(BR-37). 초안을 만들 때 customer-service 배송지를 복사해 고정하고, 주문 확정 때 주문으로 옮긴다. 이후 주소록을
 * 고치거나 지워도 바뀌지 않는다.
 *
 * <p>개인정보 — 본인 주문 조회에만 싣고, 이벤트·로그에 넣지 않는다({@link #toString()} 도 값을 숨긴다).
 */
public record ShippingAddress(
    String roadAddress,
    String detailAddress,
    @Nullable String postalCode,
    String receiverName,
    String receiverPhone,
    @Nullable String entranceMethod,
    @Nullable String entranceNote,
    @Nullable Double lat,
    @Nullable Double lng,
    Instant capturedAt) {

  /** 로그·예외 메시지에 실수로 찍혀도 개인정보가 나가지 않게 한다. */
  @Override
  public String toString() {
    return "ShippingAddress[capturedAt=" + capturedAt + "]";
  }
}
