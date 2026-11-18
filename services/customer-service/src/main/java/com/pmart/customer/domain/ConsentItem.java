package com.pmart.customer.domain;

import org.jspecify.annotations.Nullable;

/**
 * 약관 동의 항목(CU-1, 사용자 결정 2026-11-17 "추천대로"): 필수 3 + 선택 1. 계약 ConsentType 과 이름이 같다.
 *
 * <p>위치기반서비스 약관(2026-11-19, BR-59 — 사용자 "응 그렇게 해줘")은 <b>기능 필수</b>: 현재 위치 기능을 쓸 때만 필수라 로그인·구매 가능 여부의
 * 필수 목록에도, 로그인 동의 단계의 선택 목록에도 넣지 않는다(기존 회원이 다시 동의하지 않게). 앱이 그 기능을 쓰기 전에 받는다.
 */
public enum ConsentItem {
  /** 이용약관(필수). */
  TERMS_OF_SERVICE(true, null, false),
  /** 개인정보 수집·이용(필수). */
  PRIVACY_POLICY(true, null, false),
  /** 만 14세 이상(필수). */
  AGE_OVER_14(true, null, false),
  /** 마케팅 정보 수신(선택). 거부해도 구매 제한 사유가 아니다(Commerce 4.5). 로그인 전 기기에서도 받는다. */
  MARKETING(false, null, true),
  /** 위치기반서비스 이용약관(기능 필수 — 현재 위치). 로그인 전 기기에서도 받는다. */
  LOCATION_BASED_SERVICE(false, "CURRENT_LOCATION", true);

  private final boolean required;
  private final @Nullable String requiredFor;
  private final boolean device;

  ConsentItem(boolean required, @Nullable String requiredFor, boolean device) {
    this.required = required;
    this.requiredFor = requiredFor;
    this.device = device;
  }

  /** 로그인·구매 가능 여부의 필수 항목. */
  public boolean required() {
    return required;
  }

  /** 이 기능을 쓸 때만 필수(계약 requiredFor). 없으면 기능 필수가 아니다. */
  public @Nullable String requiredFor() {
    return requiredFor;
  }

  /** 로그인 전 기기 동의로 받는 항목. */
  public boolean device() {
    return device;
  }
}
