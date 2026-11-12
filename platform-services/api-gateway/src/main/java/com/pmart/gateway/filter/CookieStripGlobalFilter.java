package com.pmart.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 업스트림으로 가는 요청에서 {@code Cookie} 헤더를 지운다 — 예전 {@code default-filters: RemoveRequestHeader=Cookie} 를
 * 대신한다. PMart API 는 Bearer 토큰만 쓰므로 브라우저 쿠키를 서비스에 넘길 이유가 없다.
 *
 * <p>예외: 라우트 메타데이터 {@value #KEEP_COOKIES} 가 {@code true} 인 라우트(Keycloak 로그인 화면 중계 {@code /iam/**},
 * ADR-0056 결정 11). Keycloak 은 로그인 화면 → 비밀번호 제출 사이를 쿠키({@code AUTH_SESSION_ID}·{@code KC_RESTART})로
 * 잇는다 — 지우면 비밀번호가 맞아도 로그인이 실패한다.
 */
@Component
public class CookieStripGlobalFilter implements GlobalFilter, Ordered {

  /** 쿠키를 넘기는 라우트 표시(라우트 {@code metadata}). */
  public static final String KEEP_COOKIES = "pmart-keep-cookies";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (keepsCookies(route)
        || !exchange.getRequest().getHeaders().containsHeader(HttpHeaders.COOKIE)) {
      return chain.filter(exchange);
    }
    return chain.filter(
        exchange.mutate().request(r -> r.headers(h -> h.remove(HttpHeaders.COOKIE))).build());
  }

  static boolean keepsCookies(Route route) {
    if (route == null) {
      return false;
    }
    Object v = route.getMetadata().get(KEEP_COOKIES);
    return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
  }

  /** 처리율 제한(HIGHEST+100) 뒤, 라우팅(NettyRoutingFilter) 전. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 200;
  }
}
