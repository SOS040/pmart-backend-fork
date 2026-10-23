package com.pmart.gateway.filter;

import com.pmart.gateway.GatewayProperties;
import com.pmart.gateway.security.ProblemResponseWriter;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.function.Function;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * 라우트된 요청의 처리율 제한(Redis 토큰 버킷, {@link RedisRateLimiter}).
 *
 * <ul>
 *   <li>유효한 JWT → subject 단위 버킷({@code user:<sub>}), 그 외(공개 작업) → 클라이언트 IP 단위({@code ip:<addr>}).
 *   <li>초과 시 429 Problem({@code RATE_LIMITED}, retryable) + {@code Retry-After}. 남은 토큰은 {@code
 *       X-RateLimit-*} 헤더.
 *   <li>Redis 장애 시 {@link RedisRateLimiter} 가 허용으로 처리한다(fail-open) — 처리율 제한 때문에 전체 트래픽을 막지 않는다.
 *   <li>보안 체인이 거부한 요청(401/403)·라우트 없는 요청(404)은 여기까지 오지 않는다.
 * </ul>
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

  static final String ANONYMOUS_TIER = "pmart-anonymous";
  static final String AUTHENTICATED_TIER = "pmart-authenticated";

  private final RedisRateLimiter limiter;
  private final ProblemResponseWriter problems;
  private final boolean enabled;
  private final List<PathPattern> exemptPaths;
  private final Function<ServerWebExchange, InetSocketAddress> clientAddress;

  public RateLimitGlobalFilter(
      RedisRateLimiter limiter, GatewayProperties properties, ProblemResponseWriter problems) {
    this.limiter = limiter;
    this.problems = problems;
    GatewayProperties.RateLimit config = properties.rateLimit();
    this.enabled = config.enabled();
    this.exemptPaths =
        config.exemptPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    this.clientAddress =
        config.trustedProxies() > 0
            ? XForwardedRemoteAddressResolver.maxTrustedIndex(config.trustedProxies())::resolve
            : exchange -> exchange.getRequest().getRemoteAddress();
    if (enabled) {
      limiter.getConfig().put(ANONYMOUS_TIER, toConfig(config.anonymous()));
      limiter.getConfig().put(AUTHENTICATED_TIER, toConfig(config.authenticated()));
    }
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (!enabled || isExempt(exchange)) {
      return chain.filter(exchange);
    }
    return exchange
        .getPrincipal()
        .filter(RateLimitGlobalFilter::isAuthenticated)
        .map(p -> new Key(AUTHENTICATED_TIER, "user:" + p.getName()))
        .switchIfEmpty(Mono.fromSupplier(() -> new Key(ANONYMOUS_TIER, "ip:" + clientIp(exchange))))
        .flatMap(key -> limiter.isAllowed(key.tier(), key.id()))
        .flatMap(
            response -> {
              HttpHeaders headers = exchange.getResponse().getHeaders();
              response.getHeaders().forEach(headers::set);
              if (response.isAllowed()) {
                return chain.filter(exchange);
              }
              headers.set(HttpHeaders.RETRY_AFTER, "1");
              return problems.write(
                  exchange,
                  HttpStatus.TOO_MANY_REQUESTS,
                  "RATE_LIMITED",
                  "요청이 너무 많습니다. 잠시 후 다시 시도하세요.",
                  true);
            });
  }

  private boolean isExempt(ServerWebExchange exchange) {
    PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
    return exemptPaths.stream().anyMatch(p -> p.matches(path));
  }

  private String clientIp(ServerWebExchange exchange) {
    InetSocketAddress address = clientAddress.apply(exchange);
    if (address == null) {
      return "unknown";
    }
    return address.getAddress() != null
        ? address.getAddress().getHostAddress()
        : address.getHostString();
  }

  private static boolean isAuthenticated(Principal principal) {
    return principal instanceof Authentication a
        && a.isAuthenticated()
        && !(a instanceof AnonymousAuthenticationToken);
  }

  private static RedisRateLimiter.Config toConfig(GatewayProperties.Bucket bucket) {
    return new RedisRateLimiter.Config()
        .setReplenishRate(bucket.replenishRate())
        .setBurstCapacity(bucket.burstCapacity())
        .setRequestedTokens(1);
  }

  @Override
  public int getOrder() {
    // 라우팅(NettyRoutingFilter, LOWEST) 보다 앞, 요청 변형 필터들보다도 앞에서 거른다.
    return Ordered.HIGHEST_PRECEDENCE + 100;
  }

  private record Key(String tier, String id) {}
}
