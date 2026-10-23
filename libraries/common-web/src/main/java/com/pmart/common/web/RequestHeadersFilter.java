package com.pmart.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공통 헤더를 읽어 요청 속성에 {@link RequestHeaders}로 저장한다. 설정에 따라 변경 요청(POST/PUT/PATCH/DELETE)의 {@code
 * Idempotency-Key}·{@code X-Source-System} 누락을 400으로 거절한다.
 *
 * <p>거절은 {@link PmartException}을 던져 {@link ProblemDetailAdvice}가 처리하도록 한다. 필터는 DispatcherServlet
 * 밖이므로 예외를 직접 응답으로 쓴다.
 */
public class RequestHeadersFilter extends OncePerRequestFilter {

  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final CommonWebProperties properties;
  private final ProblemDetailWriter writer;

  public RequestHeadersFilter(CommonWebProperties properties, ProblemDetailWriter writer) {
    this.properties = properties;
    this.writer = writer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RequestHeaders headers =
        new RequestHeaders(
            trimToNull(request.getHeader(RequestHeaders.IDEMPOTENCY_KEY)),
            trimToNull(request.getHeader(RequestHeaders.SOURCE_SYSTEM)),
            parseVersion(request.getHeader(RequestHeaders.EXPECTED_VERSION)),
            trimToNull(request.getHeader(RequestHeaders.TRACEPARENT)));
    request.setAttribute(RequestHeaders.ATTRIBUTE, headers);

    if (properties.isRequireIdempotencyKey()
        && MUTATING.contains(request.getMethod())
        && isGuardedPath(request.getRequestURI())) {
      if (headers.idempotencyKey() == null) {
        writer.write(
            request,
            response,
            new PmartException(
                ErrorCode.VALIDATION_FAILED,
                "변경 요청에는 헤더 " + RequestHeaders.IDEMPOTENCY_KEY + " 가 필요합니다."));
        return;
      }
      if (headers.sourceSystem() == null) {
        writer.write(
            request,
            response,
            new PmartException(
                ErrorCode.VALIDATION_FAILED,
                "변경 요청에는 헤더 " + RequestHeaders.SOURCE_SYSTEM + " 가 필요합니다."));
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean isGuardedPath(String uri) {
    for (String prefix : properties.getGuardedPathPrefixes()) {
      if (uri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String trimToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static @Nullable Integer parseVersion(@Nullable String value) {
    String v = trimToNull(value);
    if (v == null) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(v);
      return parsed < 0 ? null : parsed;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
