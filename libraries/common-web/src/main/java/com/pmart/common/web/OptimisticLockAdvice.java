package com.pmart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 저장 시 낙관적 잠금 실패(JPA {@code @Version}, 조건부 갱신)를 VERSION_CONFLICT(409, 재시도 가능)로 응답한다. 읽은 뒤 저장 사이에 다른
 * 요청이 먼저 바꾼 경우다({@link RequestHeaders#checkExpectedVersion(long)} 는 저장 전 선검사).
 *
 * <p>spring-tx 가 있는 서비스에서만 등록된다. {@link ProblemDetailAdvice} 의 포괄 처리보다 먼저 적용되도록 순서를 앞에 둔다.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class OptimisticLockAdvice {

  private final ProblemDetailAdvice delegate;

  public OptimisticLockAdvice(ProblemDetailAdvice delegate) {
    this.delegate = delegate;
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handle(OptimisticLockingFailureException e, HttpServletRequest request) {
    return delegate.handlePmart(conflict(e), request);
  }

  static PmartException conflict(@Nullable Throwable cause) {
    return new PmartException(
        ErrorCode.VERSION_CONFLICT.name(),
        ErrorCode.VERSION_CONFLICT.status(),
        "대상이 다른 요청으로 먼저 바뀌었습니다. 다시 조회한 뒤 요청하세요.",
        ErrorCode.VERSION_CONFLICT.retryable(),
        cause);
  }
}
