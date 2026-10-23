package com.pmart.gateway.security;

import com.pmart.gateway.filter.RequestIdWebFilter;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 자체 오류(401/403/429)를 RFC 9457 {@code application/problem+json} 으로 쓴다. 형식은
 * contracts/openapi/_common.yaml ErrorResponse 와 같다(type/title/status/detail/instance +
 * code/traceId/requestId/retryable).
 */
@Component
public class ProblemResponseWriter {

  static final String TYPE_BASE = "https://problems.pmart.local/";

  private final JsonMapper mapper;
  private final ObjectProvider<Tracer> tracer;

  public ProblemResponseWriter(JsonMapper mapper, ObjectProvider<Tracer> tracer) {
    this.mapper = mapper;
    this.tracer = tracer;
  }

  public Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String code, String detail) {
    return write(exchange, status, code, detail, false);
  }

  public Mono<Void> write(
      ServerWebExchange exchange,
      HttpStatus status,
      String code,
      String detail,
      boolean retryable) {
    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.empty();
    }
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", TYPE_BASE + code);
    body.put("title", status.getReasonPhrase());
    body.put("status", status.value());
    body.put("detail", detail);
    body.put("instance", exchange.getRequest().getPath().value());
    body.put("code", code);
    body.put("retryable", retryable);
    String requestId = response.getHeaders().getFirst(RequestIdWebFilter.HEADER);
    if (requestId != null) {
      body.put("requestId", requestId);
    }
    Tracer t = tracer.getIfAvailable();
    if (t != null && t.currentSpan() != null) {
      body.put("traceId", t.currentSpan().context().traceId());
    }
    byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    DataBuffer buffer = response.bufferFactory().wrap(bytes);
    return response.writeWith(Mono.just(buffer));
  }
}
