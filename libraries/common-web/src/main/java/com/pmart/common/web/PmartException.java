package com.pmart.common.web;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * 서비스가 던지는 업무·기술 예외. {@link ProblemDetailAdvice}가 RFC 9457 응답으로 바꾼다.
 *
 * <p>code는 공통 {@link ErrorCode} 또는 서비스별 문자열(예 ORDER_NOT_FOUND, SKU_NOT_SALEABLE). 메시지에 개인정보·비밀을 넣지
 * 않는다.
 */
public class PmartException extends RuntimeException {

  /** 필드별 오류 (ErrorResponse.details[]). */
  public record Detail(String field, String reason) {}

  private final String code;
  private final HttpStatus status;
  private final boolean retryable;
  private final List<Detail> details;

  public PmartException(ErrorCode code, String message) {
    this(code.name(), code.status(), message, code.retryable(), List.of());
  }

  public PmartException(ErrorCode code, String message, List<Detail> details) {
    this(code.name(), code.status(), message, code.retryable(), details);
  }

  public PmartException(
      String code, HttpStatus status, String message, boolean retryable, List<Detail> details) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
    this.status = Objects.requireNonNull(status, "status");
    this.retryable = retryable;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public PmartException(
      String code, HttpStatus status, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.status = Objects.requireNonNull(status, "status");
    this.retryable = retryable;
    this.details = List.of();
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }

  public List<Detail> details() {
    return details;
  }

  // ---- 자주 쓰는 생성 헬퍼 ----

  public static PmartException notFound(String code, String message) {
    return new PmartException(code, HttpStatus.NOT_FOUND, message, false, List.of());
  }

  public static PmartException invalidState(String message) {
    return new PmartException(ErrorCode.INVALID_STATE, message);
  }

  public static PmartException versionConflict(String message) {
    return new PmartException(ErrorCode.VERSION_CONFLICT, message);
  }

  public static PmartException idempotencyConflict(String message) {
    return new PmartException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
  }

  /** 정책값 미설정. 0/무제한으로 대체하지 않고 해당 신규 실행만 보류한다. */
  public static PmartException configRequired(String policyKey) {
    return new PmartException(
        ErrorCode.CONFIG_REQUIRED, "정책값 미설정: " + policyKey + ". 승인된 설정 전에는 실행하지 않습니다.");
  }

  public static PmartException unknownResult(String message) {
    return new PmartException(ErrorCode.UNKNOWN_RESULT, message);
  }

  public static PmartException dependencyUnavailable(String message, Throwable cause) {
    return new PmartException(
        ErrorCode.DEPENDENCY_UNAVAILABLE.name(),
        ErrorCode.DEPENDENCY_UNAVAILABLE.status(),
        message,
        true,
        cause);
  }
}
