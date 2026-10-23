package com.pmart.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 사가/사건 상관관계 ID. 요청 헤더 {@code X-Correlation-Id}가 있으면 쓰고 없으면 생성한다. MDC에 {@code correlationId}로 넣어
 * 로그에 찍히게 하고 응답 헤더로 돌려준다. 원격 전파는 Micrometer Tracing baggage(remote-fields)가 맡는다.
 *
 * <p>traceId는 요청마다 바뀌지만 correlationId는 사가 전체(여러 요청·이벤트)에 걸쳐 유지된다.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final String SOURCE_SYSTEM_HEADER = "X-Source-System";
  public static final String SOURCE_SYSTEM_MDC_KEY = "sourceSystem";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = sanitize(request.getHeader(HEADER));
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }
    String sourceSystem = sanitize(request.getHeader(SOURCE_SYSTEM_HEADER));
    MDC.put(MDC_KEY, correlationId);
    if (sourceSystem != null) {
      MDC.put(SOURCE_SYSTEM_MDC_KEY, sourceSystem);
    }
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      MDC.remove(SOURCE_SYSTEM_MDC_KEY);
    }
  }

  /** 로그 인젝션 방지: 길이 제한·제어문자 제거. */
  static @Nullable String sanitize(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    if (v.isEmpty() || v.length() > 128) {
      return null;
    }
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        return null;
      }
    }
    return v;
  }
}
