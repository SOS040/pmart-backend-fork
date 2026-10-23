package com.pmart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PmartException}·오류 코드를 RFC 9457 {@link ProblemDetail}로 만들고 필요하면 서블릿 응답에 직접 쓴다.
 *
 * <p>표준 멤버 type/title/status/detail/instance + 확장 code/traceId/requestId/retryable/details
 * (contracts/_common.yaml ErrorResponse). traceId는 관측 라이브러리가 MDC에 넣은 값을 읽는다(없으면 생략).
 */
public class ProblemDetailWriter {

  public static final String MDC_TRACE_ID = "traceId";
  private static final String TYPE_PREFIX = "https://problems.pmart.local/";

  private final ObjectMapper objectMapper;

  public ProblemDetailWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ProblemDetail toProblemDetail(
      String code,
      HttpStatus status,
      @Nullable String detail,
      boolean retryable,
      List<PmartException.Detail> details,
      @Nullable String requestId,
      @Nullable String instance) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(TYPE_PREFIX + code));
    pd.setTitle(code);
    pd.setDetail(detail);
    if (instance != null) {
      pd.setInstance(URI.create(instance));
    }
    pd.setProperty("code", code);
    String traceId = MDC.get(MDC_TRACE_ID);
    if (traceId != null) {
      pd.setProperty("traceId", traceId);
    }
    if (requestId != null) {
      pd.setProperty("requestId", requestId);
    }
    pd.setProperty("retryable", retryable);
    if (details != null && !details.isEmpty()) {
      pd.setProperty(
          "details",
          details.stream().map(d -> Map.of("field", d.field(), "reason", d.reason())).toList());
    }
    return pd;
  }

  public ProblemDetail toProblemDetail(
      PmartException e, @Nullable String requestId, @Nullable String instance) {
    return toProblemDetail(
        e.code(), e.status(), e.getMessage(), e.retryable(), e.details(), requestId, instance);
  }

  /** 필터 등 DispatcherServlet 밖에서 오류 응답을 쓸 때 사용. */
  public void write(HttpServletRequest request, HttpServletResponse response, PmartException e)
      throws IOException {
    String requestId = request.getHeader(RequestHeaders.IDEMPOTENCY_KEY);
    ProblemDetail pd = toProblemDetail(e, requestId, request.getRequestURI());
    response.setStatus(e.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(objectMapper.writeValueAsString(pd));
    response.getWriter().flush();
  }
}
