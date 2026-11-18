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
    @Nullable Double lng,
    @Nullable String geoSource) {

  /** 좌표 출처(2026-11-18) — 고객이 지도 핀으로 고름. */
  public static final String GEO_PIN = "PIN";

  /** 좌표 출처 — 핀이 없어 서버가 도로명주소를 지오코딩(delivery-serviceability, ADR-0023 과 같은 변환). */
  public static final String GEO_GEOCODE = "GEOCODE";

  /** 좌표가 없을 때 서버 지오코딩 결과로 채운 사본(이미 있으면 그대로). */
  public AddressData withGeocode(double geoLat, double geoLng) {
    if (lat != null && lng != null) {
      return this;
    }
    return new AddressData(
        label,
        roadAddress,
        detailAddress,
        postalCode,
        receiverName,
        receiverPhone,
        entranceMethod,
        entranceNote,
        geoLat,
        geoLng,
        GEO_GEOCODE);
  }

  @Override
  public String toString() {
    return "AddressData[개인정보 생략]";
  }
}
