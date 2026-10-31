package com.pmart.delivery.serviceability.application;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 주소 ↔ 좌표 변환(ADR-0023). 구현은 infrastructure 의 카카오 로컬 어댑터.
 *
 * <p>실패는 {@code PmartException}: 장애·시간 초과 DEPENDENCY_UNAVAILABLE, 사용량 소진 QUOTA_EXCEEDED(429), 키
 * 미설정·거절 CONFIG_REQUIRED. 0건은 실패가 아니라 빈 목록이다.
 */
public interface Geocoder {

  record Place(
      @Nullable String roadAddress,
      @Nullable String jibunAddress,
      @Nullable String zoneNo,
      @Nullable String buildingName,
      double lat,
      double lng) {}

  /**
   * @param exact true 면 정확 일치만(판정용), false 면 유사 검색(화면용)
   */
  List<Place> search(String query, boolean exact);

  List<Place> reverse(double lat, double lng);
}
