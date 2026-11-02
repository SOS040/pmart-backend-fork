package com.pmart.order.api;

import com.pmart.order.api.generated.model.ShippingAddressGeo;
import com.pmart.order.domain.ShippingAddress;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 주문 당시 배송지 사본(BR-37) → 계약 모델. 본인 초안·주문 응답에만 쓴다. */
final class ShippingMapping {

  private ShippingMapping() {}

  static com.pmart.order.api.generated.model.@Nullable ShippingAddress of(
      @Nullable ShippingAddress s) {
    if (s == null) {
      return null;
    }
    var out =
        new com.pmart.order.api.generated.model.ShippingAddress()
            .roadAddress(s.roadAddress())
            .detailAddress(s.detailAddress())
            .postalCode(s.postalCode())
            .receiverName(s.receiverName())
            .receiverPhone(s.receiverPhone())
            .entranceNote(s.entranceNote())
            .capturedAt(s.capturedAt().atOffset(ZoneOffset.UTC));
    if (s.entranceMethod() != null) {
      try {
        out.entranceMethod(
            com.pmart.order.api.generated.model.ShippingAddress.EntranceMethodEnum.fromValue(
                s.entranceMethod()));
      } catch (IllegalArgumentException e) {
        // 계약에 없는 값은 싣지 않는다(지어내지 않음)
      }
    }
    if (s.lat() != null && s.lng() != null) {
      out.geo(
          new ShippingAddressGeo()
              .lat(java.math.BigDecimal.valueOf(s.lat()))
              .lng(java.math.BigDecimal.valueOf(s.lng())));
    }
    return out;
  }
}
