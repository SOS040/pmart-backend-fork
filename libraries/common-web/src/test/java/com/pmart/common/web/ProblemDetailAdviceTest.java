package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ProblemDetailAdviceTest {

  private final ProblemDetailAdvice advice =
      new ProblemDetailAdvice(new ProblemDetailWriter(new ObjectMapper()));

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void pmartExceptionBecomesRfc9457WithExtensions() {
    MDC.put(ProblemDetailWriter.MDC_TRACE_ID, "trace-abc");
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/api/v1/orders/1/cancellations");
    req.addHeader(RequestHeaders.IDEMPOTENCY_KEY, "req-9");
    PmartException e =
        new PmartException(
            ErrorCode.INSUFFICIENT_STOCK,
            "재고 부족",
            List.of(new PmartException.Detail("lines[0].qty", "available=2")));

    ProblemDetail pd = advice.handlePmart(e, req);

    assertThat(pd.getStatus()).isEqualTo(422);
    assertThat(pd.getTitle()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(pd.getType().toString()).endsWith("/INSUFFICIENT_STOCK");
    assertThat(pd.getInstance().toString()).isEqualTo("/api/v1/orders/1/cancellations");
    assertThat(pd.getProperties()).containsEntry("code", "INSUFFICIENT_STOCK");
    assertThat(pd.getProperties()).containsEntry("traceId", "trace-abc");
    assertThat(pd.getProperties()).containsEntry("requestId", "req-9");
    assertThat(pd.getProperties()).containsEntry("retryable", false);
    assertThat(pd.getProperties().get("details")).isNotNull();
  }

  @Test
  void serviceSpecificCodeAndStatusArePreserved() {
    PmartException e = PmartException.notFound("ORDER_NOT_FOUND", "주문 없음");
    ProblemDetail pd = advice.handlePmart(e, new MockHttpServletRequest("GET", "/api/v1/orders/x"));
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(pd.getProperties()).containsEntry("code", "ORDER_NOT_FOUND");
  }

  @Test
  void unexpectedExceptionHidesInternalMessage() {
    ProblemDetail pd =
        advice.handleUnexpected(
            new IllegalStateException("db password=secret"),
            new MockHttpServletRequest("GET", "/api/v1/x"));
    assertThat(pd.getStatus()).isEqualTo(500);
    assertThat(pd.getDetail()).doesNotContain("secret");
    assertThat(pd.getProperties()).containsEntry("code", "INTERNAL_ERROR");
  }

  @Test
  void configRequiredIs422AndNotRetryable() {
    ProblemDetail pd =
        advice.handlePmart(
            PmartException.configRequired("slot.capacity"),
            new MockHttpServletRequest("POST", "/internal/slot-holds"));
    assertThat(pd.getStatus()).isEqualTo(422);
    assertThat(pd.getProperties()).containsEntry("code", "CONFIG_REQUIRED");
    assertThat(pd.getProperties()).containsEntry("retryable", false);
  }
}
