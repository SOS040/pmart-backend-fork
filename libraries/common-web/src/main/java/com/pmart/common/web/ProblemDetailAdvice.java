package com.pmart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 모든 컨트롤러 예외를 RFC 9457 응답으로 통일한다. Spring MVC 자체 예외(404, 405, 바인딩 실패 등)는 Spring Boot의 {@code
 * spring.mvc.problemdetails.enabled=true}(auto-config에서 기본 켬)가 처리하고, 여기서는 프로젝트 예외·검증·그 외를 맡는다.
 *
 * <p>500 응답에는 내부 예외 메시지를 노출하지 않는다(개인정보·비밀 보호). 로그에만 남긴다.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ProblemDetailAdvice {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

  private final ProblemDetailWriter writer;

  public ProblemDetailAdvice(ProblemDetailWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(PmartException.class)
  public ProblemDetail handlePmart(PmartException e, HttpServletRequest request) {
    if (e.status().is5xxServerError()) {
      log.warn(
          "{} {} -> {}: {}",
          request.getMethod(),
          request.getRequestURI(),
          e.code(),
          e.getMessage());
    }
    return writer.toProblemDetail(e, requestId(request), request.getRequestURI());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<PmartException.Detail> details =
        e.getBindingResult().getFieldErrors().stream()
            .map(fe -> new PmartException.Detail(fe.getField(), defaultMessage(fe)))
            .toList();
    return writer.toProblemDetail(
        ErrorCode.VALIDATION_FAILED.name(),
        HttpStatus.BAD_REQUEST,
        "요청 본문 검증 실패",
        false,
        details,
        requestId(request),
        request.getRequestURI());
  }

  /** 계약의 쿼리·경로 파라미터 제약(@Size·@DecimalMin 등, 생성 인터페이스의 메서드 검증) 위반. 본문 검증과 같은 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException e, HttpServletRequest request) {
    List<PmartException.Detail> details =
        e.getConstraintViolations().stream()
            .map(v -> new PmartException.Detail(v.getPropertyPath().toString(), v.getMessage()))
            .toList();
    return writer.toProblemDetail(
        ErrorCode.VALIDATION_FAILED.name(),
        HttpStatus.BAD_REQUEST,
        "요청 값 검증 실패",
        false,
        details,
        requestId(request),
        request.getRequestURI());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail handleResponseStatus(ResponseStatusException e, HttpServletRequest request) {
    HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    String code = status.is4xxClientError() ? codeFor(status) : ErrorCode.INTERNAL_ERROR.name();
    return writer.toProblemDetail(
        code, status, e.getReason(), false, List.of(), requestId(request), request.getRequestURI());
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled exception {} {}", request.getMethod(), request.getRequestURI(), e);
    return writer.toProblemDetail(
        ErrorCode.INTERNAL_ERROR.name(),
        HttpStatus.INTERNAL_SERVER_ERROR,
        "내부 오류가 발생했습니다.",
        false,
        List.of(),
        requestId(request),
        request.getRequestURI());
  }

  private static String codeFor(HttpStatus status) {
    return switch (status) {
      case NOT_FOUND -> ErrorCode.NOT_FOUND.name();
      case FORBIDDEN, UNAUTHORIZED -> ErrorCode.FORBIDDEN.name();
      case CONFLICT -> ErrorCode.INVALID_STATE.name();
      default -> ErrorCode.VALIDATION_FAILED.name();
    };
  }

  private static String defaultMessage(FieldError fe) {
    String m = fe.getDefaultMessage();
    return m == null ? "invalid" : m;
  }

  private static @Nullable String requestId(HttpServletRequest request) {
    Object attr = request.getAttribute(RequestHeaders.ATTRIBUTE);
    if (attr instanceof RequestHeaders h && h.idempotencyKey() != null) {
      return h.idempotencyKey();
    }
    return request.getHeader(RequestHeaders.IDEMPOTENCY_KEY);
  }
}
