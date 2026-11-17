package com.pmart.customer.domain;

/**
 * 약관 동의 항목(CU-1, 사용자 결정 2026-11-17 "추천대로"): 필수 3 + 선택 1. 계약 ConsentType 과 이름이 같다. 위치기반서비스 약관은 현재 위치
 * 기능을 만들 때 더한다.
 */
public enum ConsentItem {
  /** 이용약관(필수). */
  TERMS_OF_SERVICE(true),
  /** 개인정보 수집·이용(필수). */
  PRIVACY_POLICY(true),
  /** 만 14세 이상(필수). */
  AGE_OVER_14(true),
  /** 마케팅 정보 수신(선택). 거부해도 구매 제한 사유가 아니다(Commerce 4.5). */
  MARKETING(false);

  private final boolean required;

  ConsentItem(boolean required) {
    this.required = required;
  }

  public boolean required() {
    return required;
  }
}
