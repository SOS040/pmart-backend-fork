package com.pmart.riderauth.api;

import com.pmart.common.web.ProblemDetailWriter;
import com.pmart.riderauth.application.RiderLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AUTH_RESTRICTED(423): 계약 RestrictedError — Problem Details 에 retryAfterSeconds·restrictedUntil,
 * Retry-After 헤더.
 */
@RestControllerAdvice(assignableTypes = LoginsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RestrictedAdvice {

  private final ProblemDetailWriter problems;

  RestrictedAdvice(ProblemDetailWriter problems) {
    this.problems = problems;
  }

  @ExceptionHandler(RiderLoginService.RestrictedException.class)
  ResponseEntity<ProblemDetail> restricted(
      RiderLoginService.RestrictedException e, HttpServletRequest request) {
    ProblemDetail pd =
        problems.toProblemDetail(
            "AUTH_RESTRICTED",
            HttpStatus.LOCKED,
            e.getMessage(),
            false,
            List.of(),
            request.getHeader("Idempotency-Key"),
            request.getRequestURI());
    pd.setProperty("retryAfterSeconds", e.retryAfterSeconds());
    pd.setProperty("restrictedUntil", e.until().atOffset(ZoneOffset.UTC).toString());
    return ResponseEntity.status(HttpStatus.LOCKED)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
