package com.pmart.auth.domain;

import org.jspecify.annotations.Nullable;

/**
 * 네이버 로그인 콜백 중계(2026-11-19, 계약 0.5.0 — 고객 앱 BR-66). 넘길 곳은 앱마다 고정 — 요청에서 목적지를 받지 않는다(열린 리다이렉트 없음).
 * 쿼리는 받은 그대로 붙인다(다시 인코딩하지 않음 — PKCE 교환에서 state·code 가 바뀌면 안 된다).
 */
public final class OAuthCallbackRules {

  private OAuthCallbackRules() {}

  public static final String CUSTOMER_NAVER = "com.pmart.customer://oauth/naver";
  public static final String RIDER_NAVER = "com.pmart.rider://oauth/naver";
  public static final int MAX_QUERY = 4096;

  /** 넘길 주소. 쿼리가 너무 길면 null(400). */
  public static @Nullable String location(String target, @Nullable String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return target;
    }
    if (rawQuery.length() > MAX_QUERY
        || rawQuery.indexOf('\r') >= 0
        || rawQuery.indexOf('\n') >= 0) {
      return null;
    }
    return target + "?" + rawQuery;
  }
}
