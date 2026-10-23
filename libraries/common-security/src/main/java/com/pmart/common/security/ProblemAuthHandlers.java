package com.pmart.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 401/403 을 RFC 9457 {@code application/problem+json} 으로 쓴다 (common-web 에 의존하지 않도록 직접 작성). 확장 멤버는
 * contracts/_common.yaml ErrorResponse 와 동일: code, traceId, retryable.
 */
public class ProblemAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final String TYPE_PREFIX = "https://problems.pmart.local/";
  private final ObjectMapper mapper;

  public ProblemAuthHandlers(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    write(request, response, 401, "UNAUTHENTICATED", "인증이 필요합니다.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    write(request, response, 403, "FORBIDDEN", "권한이 없습니다.");
  }

  private void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String detail)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", TYPE_PREFIX + code);
    body.put("title", code);
    body.put("status", status);
    body.put("detail", detail);
    body.put("instance", request.getRequestURI());
    body.put("code", code);
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      body.put("traceId", traceId);
    }
    body.put("retryable", false);
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(mapper.writeValueAsString(body));
    response.getWriter().flush();
  }
}
