package com.pmart.gateway.filter;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@code X-Request-Id} 를 요청 단위로 보장한다. 클라이언트가 보낸 값은 유지, 없으면 발급. 응답 헤더로도 돌려주며 업스트림에는 요청 헤더 그대로 전달된다.
 * (traceId 는 Micrometer Tracing 이 W3C {@code traceparent} 로 발급·전파 — 여기서 만들지 않는다.)
 *
 * <p>보안 필터 체인보다 먼저 실행되어 401/403 Problem 응답에도 requestId 가 실린다.
 */
@Component
public class RequestIdWebFilter implements WebFilter, Ordered {

  public static final String HEADER = "X-Request-Id";
  private static final int MAX_LENGTH = 128;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
    String requestId = isUsable(incoming) ? incoming : UUID.randomUUID().toString();
    ServerHttpRequest request =
        exchange.getRequest().mutate().headers(h -> h.set(HEADER, requestId)).build();
    exchange.getResponse().getHeaders().set(HEADER, requestId);
    return chain.filter(exchange.mutate().request(request).build());
  }

  private static boolean isUsable(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= MAX_LENGTH
        && value.chars().allMatch(c -> c > 0x20 && c < 0x7f);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
