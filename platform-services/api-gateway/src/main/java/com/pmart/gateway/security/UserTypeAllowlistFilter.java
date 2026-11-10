package com.pmart.gateway.security;

import com.pmart.gateway.security.UserTypeAllowlist.Decision;
import com.pmart.gateway.security.UserTypeAllowlist.Verdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 경로별 사용자 종류 허용 목록 적용(ADR-0054 §6.1). Spring Security(-100) 뒤에서 인증된 요청만 본다 — 토큰 없는 요청·공개 작업 판정은 보안
 * 체인 몫이다.
 *
 * <ul>
 *   <li>{@code RECORD}(기본): 막지 않고 어긋난 요청을 경고 로그 + 지표({@code
 *       pmart_gateway_user_type_allowlist_total{verdict}})로만 남긴다 — 목록에 빠진 경로를 찾는 단계.
 *   <li>{@code ENFORCE}: 어긋나면 403 {@code USER_TYPE_NOT_ALLOWED}(목록에 없는 경로도 거부 — 기본 거부).
 *   <li>{@code OFF}: 보지 않는다.
 * </ul>
 *
 * 사용자 종류는 발급자 규칙이 정한 값({@link IssuedToken})이다. 업무 인가(소유권·센터·역할)는 계속 서비스가 한다.
 */
public class UserTypeAllowlistFilter implements WebFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(UserTypeAllowlistFilter.class);

  /** {@code pmart.gateway.user-type-allowlist.*}. */
  @ConfigurationProperties(prefix = "pmart.gateway.user-type-allowlist")
  public record Settings(@Nullable Mode mode) {
    public Settings {
      mode = mode == null ? Mode.RECORD : mode;
    }
  }

  public enum Mode {
    OFF,
    RECORD,
    ENFORCE
  }

  private final UserTypeAllowlist allowlist;
  private final Mode mode;
  private final ProblemResponseWriter problems;
  private final @Nullable MeterRegistry meters;

  public UserTypeAllowlistFilter(
      UserTypeAllowlist allowlist,
      Mode mode,
      ProblemResponseWriter problems,
      @Nullable MeterRegistry meters) {
    this.allowlist = allowlist;
    this.mode = mode;
    this.problems = problems;
    this.meters = meters;
  }

  @Override
  public int getOrder() {
    return 0; // WebFilterChainProxy(-100) 뒤 — 인증 결과가 있다
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    HttpMethod method = exchange.getRequest().getMethod();
    if (mode == Mode.OFF || !path.startsWith("/api/") || HttpMethod.OPTIONS.equals(method)) {
      return chain.filter(exchange);
    }
    return exchange
        .getPrincipal()
        .filter(UserTypeAllowlistFilter::isAuthenticated)
        .map(p -> typeOf((Authentication) p))
        .flatMap(
            type -> {
              Decision d = allowlist.decide(method, path, type);
              if (d.verdict() == Verdict.ALLOWED) {
                return chain.filter(exchange).thenReturn(Boolean.TRUE);
              }
              count(d.verdict());
              // 경로 원문 대신 맞은 패턴을 남긴다(경로 안 ID 를 로그에 넣지 않음). 없으면 첫 두 마디만
              String where = d.pattern() != null ? d.pattern() : prefix(path);
              log.warn(
                  "user-type allowlist {} mode={} method={} path={} userType={}",
                  d.verdict(),
                  mode,
                  method,
                  where,
                  type);
              if (mode == Mode.ENFORCE) {
                return problems
                    .write(
                        exchange,
                        HttpStatus.FORBIDDEN,
                        "USER_TYPE_NOT_ALLOWED",
                        "이 사용자 종류로는 호출할 수 없는 작업입니다.")
                    .thenReturn(Boolean.TRUE);
              }
              return chain.filter(exchange).thenReturn(Boolean.TRUE);
            })
        // 인증 없음(공개 작업·401 은 보안 체인 몫) — defer: 판정 쪽에서 이미 넘겼으면 다시 부르지 않는다
        .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE)))
        .then();
  }

  private void count(Verdict verdict) {
    if (meters != null) {
      Counter.builder("pmart.gateway.user.type.allowlist")
          .description("허용 목록과 어긋난 인증 요청(ADR-0054)")
          .tag("verdict", verdict.name())
          .tag("mode", mode.name())
          .register(meters)
          .increment();
    }
  }

  /** 발급자 규칙의 종류. 정할 수 없으면 "UNKNOWN"(목록의 어떤 종류와도 맞지 않는다). */
  static String typeOf(Authentication a) {
    String t = IssuedToken.userTypeOf(a);
    return t == null || t.isBlank() ? "UNKNOWN" : t;
  }

  private static boolean isAuthenticated(Principal p) {
    return p instanceof Authentication a
        && a.isAuthenticated()
        && !(a instanceof AnonymousAuthenticationToken);
  }

  static String prefix(String path) {
    String[] parts = path.split("/");
    // "", "api", "v1", "<자원>" …
    return parts.length >= 4 ? "/" + parts[1] + "/" + parts[2] + "/" + parts[3] + "/**" : path;
  }
}
