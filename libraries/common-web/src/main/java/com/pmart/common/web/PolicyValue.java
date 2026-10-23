package com.pmart.common.web;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 정책값 3값 응답 (contracts/_common.yaml PolicyValue). NOT_SET은 0/무제한이 아니다. 호출자는 NOT_SET·UNAVAILABLE이면
 * 해당 신규 실행만 CONFIG_REQUIRED로 보류한다.
 */
public record PolicyValue<T>(
    State state,
    @Nullable T value,
    @Nullable Integer policyVersion,
    @Nullable Instant effectiveFrom) {

  public enum State {
    VALUE,
    NOT_SET,
    UNAVAILABLE
  }

  public static <T> PolicyValue<T> of(T value, int policyVersion, Instant effectiveFrom) {
    return new PolicyValue<>(State.VALUE, value, policyVersion, effectiveFrom);
  }

  public static <T> PolicyValue<T> notSet() {
    return new PolicyValue<>(State.NOT_SET, null, null, null);
  }

  public static <T> PolicyValue<T> unavailable() {
    return new PolicyValue<>(State.UNAVAILABLE, null, null, null);
  }

  /** VALUE면 값을, 아니면 CONFIG_REQUIRED 예외. */
  public T require(String policyKey) {
    if (state == State.VALUE && value != null) {
      return value;
    }
    throw PmartException.configRequired(policyKey);
  }
}
