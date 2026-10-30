package com.pmart.customer.domain;

import org.jspecify.annotations.Nullable;

/** 고객이 입력한 주소(주소록·비회원 배송지 공통). entranceNote 는 출입 비밀번호일 수 있어 로그 금지. */
public record AddressData(
    @Nullable String label,
    String roadAddress,
    String detailAddress,
    @Nullable String postalCode,
    String receiverName,
    String receiverPhone,
    @Nullable String entranceMethod,
    @Nullable String entranceNote,
    @Nullable Double lat,
    @Nullable Double lng) {

  @Override
  public String toString() {
    return "AddressData[개인정보 생략]";
  }
}
