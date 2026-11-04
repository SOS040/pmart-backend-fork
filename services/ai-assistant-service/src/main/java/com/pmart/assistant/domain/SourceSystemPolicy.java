package com.pmart.assistant.domain;

import org.jspecify.annotations.Nullable;

/**
 * X-Source-System 검증(3차 검토 2). 헤더를 그대로 믿지 않는다.
 *
 * <p>토큰에 {@code src} 가 있으면 헤더와 같아야 한다. 없으면(현재 auth-service 운영자 토큰) 1차는 {@value #PHASE1_SOURCE} 하나로
 * 고정한다 — 설정 목록이 아니라 상수라서 호출자가 다른 출처를 골라 다른 출처의 기록을 볼 수 없다.
 */
public final class SourceSystemPolicy {

  public static final String PHASE1_SOURCE = "backoffice-web";

  private SourceSystemPolicy() {}

  public static boolean accepts(@Nullable String tokenSource, @Nullable String header) {
    if (header == null || header.isBlank()) {
      return false;
    }
    if (tokenSource != null && !tokenSource.isBlank()) {
      return tokenSource.equals(header);
    }
    return PHASE1_SOURCE.equals(header);
  }
}
