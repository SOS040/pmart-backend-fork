package com.pmart.delivery.serviceability.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.ProblemDetailWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 계약의 쿼리 파라미터 제약(@Size·@DecimalMin 등) 위반은 400 VALIDATION_FAILED. 공통 ProblemDetailAdvice 는 본문 검증만
 * 다뤄서 이 예외가 500 이 된다 — 공통 라이브러리에 옮기기 전까지 이 서비스에서 처리한다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class QueryValidationAdvice {

  private final ProblemDetailWriter writer;

  QueryValidationAdvice(ProblemDetailWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handle(ConstraintViolationException e, HttpServletRequest request) {
    return writer.toProblemDetail(
        ErrorCode.VALIDATION_FAILED.name(),
        HttpStatus.BAD_REQUEST,
        "요청 값 검증 실패",
        false,
        e.getConstraintViolations().stream()
            .map(v -> new PmartException.Detail(v.getPropertyPath().toString(), v.getMessage()))
            .toList(),
        null,
        request.getRequestURI());
  }
}
