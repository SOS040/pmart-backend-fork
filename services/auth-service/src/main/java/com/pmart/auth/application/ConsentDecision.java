package com.pmart.auth.application;

/**
 * 동의 화면에서 고객이 고른 한 항목(필수 동의 완료, CU-1). 항목 이름·버전은 customer-service 의 것 그대로(AuthResult 의 {@code
 * TYPE:version} 을 나눈 값) — auth 는 해석하지 않고 넘긴다.
 */
public record ConsentDecision(String consentType, String version, boolean agreed) {}
