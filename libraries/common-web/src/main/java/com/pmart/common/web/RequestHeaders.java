package com.pmart.common.web;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 변경 요청 공통 헤더 (contracts/README.md 1.1).
 *
 * <ul>
 *   <li>{@code Idempotency-Key} = requestId. 같은 키·같은 본문은 기존 결과, 다른 본문은 IDEMPOTENCY_CONFLICT.
 *   <li>{@code X-Source-System} 호출 서비스/채널.
 *   <li>{@code X-Expected-Version} 낙관적 잠금. 현재 버전과 다르면 VERSION_CONFLICT.
 *   <li>{@code traceparent} W3C Trace Context (게이트웨이 발급, 관측 라이브러리가 전파).
 * </ul>
 *
 * 컨트롤러 메서드 파라미터로 선언하면 {@link RequestHeadersArgumentResolver}가 주입한다.
 */
public record RequestHeaders(
    @Nullable String idempotencyKey,
    @Nullable String sourceSystem,
    @Nullable Integer expectedVersion,
    @Nullable String traceparent) {

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  public static final String SOURCE_SYSTEM = "X-Source-System";
  public static final String EXPECTED_VERSION = "X-Expected-Version";
  public static final String TRACEPARENT = "traceparent";

  /** 서블릿 요청 속성 키. */
  public static final String ATTRIBUTE = RequestHeaders.class.getName();

  /** 변경 요청에서 필수인 requestId. 없으면 VALIDATION_FAILED. */
  public String requireIdempotencyKey() {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "헤더 " + IDEMPOTENCY_KEY + " 가 필요합니다.");
    }
    return idempotencyKey;
  }

  public String requireSourceSystem() {
    if (sourceSystem == null || sourceSystem.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "헤더 " + SOURCE_SYSTEM + " 가 필요합니다.");
    }
    return sourceSystem;
  }

  public Optional<Integer> expectedVersionIfPresent() {
    return Optional.ofNullable(expectedVersion);
  }
}
