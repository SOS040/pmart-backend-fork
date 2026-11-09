package com.pmart.customerassistant.api;

import com.pmart.common.web.PmartException;
import com.pmart.common.web.ProblemDetailAdvice;
import com.pmart.customerassistant.application.CustomerQuestionService.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 요청 제한(C3) 초과 → 429 RATE_LIMITED(Problem Details, 공통 형식) + Retry-After(초). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitAdvice {

  private final ProblemDetailAdvice problems;

  public RateLimitAdvice(ProblemDetailAdvice problems) {
    this.problems = problems;
  }

  @ExceptionHandler(RateLimitedException.class)
  public ProblemDetail rateLimited(
      RateLimitedException e, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
    return problems.handlePmart(
        new PmartException(
            "RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), true, List.of()),
        request);
  }
}
