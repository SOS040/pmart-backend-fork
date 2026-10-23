package com.pmart.common.web;

import org.springframework.http.HttpStatus;

/**
 * 공통 오류 코드 (contracts/openapi/_common.yaml ErrorCode)와 기본 HTTP 상태.
 *
 * <p>서비스별 코드(ORDER_NOT_FOUND 등)는 각 서비스가 {@link PmartException}에 문자열로 넣는다. 여기에는 전 서비스 공통 의미만 둔다.
 */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false),
  FORBIDDEN(HttpStatus.FORBIDDEN, false),
  NOT_FOUND(HttpStatus.NOT_FOUND, false),
  INVALID_STATE(HttpStatus.CONFLICT, false),
  VERSION_CONFLICT(HttpStatus.CONFLICT, true),
  IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, false),
  CONFIG_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, false),
  INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, false),
  CAPACITY_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, false),
  DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
  UNKNOWN_RESULT(HttpStatus.SERVICE_UNAVAILABLE, false),
  UNSUPPORTED_ACTION(HttpStatus.NOT_IMPLEMENTED, false),
  APPROVAL_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, false),
  EVIDENCE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, false),
  STALE_VERSION(HttpStatus.CONFLICT, true),
  DOMAIN_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, false),
  PARTIAL_APPLIED(HttpStatus.CONFLICT, false),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

  private final HttpStatus status;
  private final boolean retryable;

  ErrorCode(HttpStatus status, boolean retryable) {
    this.status = status;
    this.retryable = retryable;
  }

  public HttpStatus status() {
    return status;
  }

  /** 기본 재시도 가능 여부. UNKNOWN_RESULT는 재시도가 아니라 원결과 조회이므로 false. */
  public boolean retryable() {
    return retryable;
  }
}
