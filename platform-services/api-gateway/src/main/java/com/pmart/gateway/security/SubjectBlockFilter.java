package com.pmart.gateway.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 주체 차단 확인(ADR-0054 ⑤, 설계 issuer-type-pairing-and-access-lists.md §6.2). 서명·발급자 규칙을 통과한 토큰이라도 그 주체가
 * 차단됐고 토큰이 차단 시각 이전에 발급됐으면({@code iat <= blockedBefore}, 초 단위) 401 {@code SUBJECT_BLOCKED}.
 *
 * <ul>
 *   <li>보안 체인(-100) 뒤, 허용 목록(0) 앞 — 인증 결과가 있다. 토큰 없는 요청·공개 작업은 보지 않는다.
 *   <li>Redis 장애·시간 초과는 <b>통과</b>(fail-open, ADR-0054 결정 H): 직원 토큰 15분, 업무 상태는 서비스가 따로 본다. 지표
 *       {@code pmart_gateway_subject_block_total{result=lookup_failed}} 와 분당 한 번 경고 로그.
 *   <li>로그에는 주체·토큰을 남기지 않는다(종류·발급자만).
 * </ul>
 */
public class SubjectBlockFilter implements WebFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(SubjectBlockFilter.class);
  private static final long WARN_EVERY_MS = 60_000;

  private final SubjectBlocklist blocklist;
  private final Duration lookupTimeout;
  private final ProblemResponseWriter problems;
  private final @Nullable MeterRegistry meters;
  private final AtomicLong lastWarn = new AtomicLong();

  public SubjectBlockFilter(
      SubjectBlocklist blocklist,
      Duration lookupTimeout,
      ProblemResponseWriter problems,
      @Nullable MeterRegistry meters) {
    this.blocklist = blocklist;
    this.lookupTimeout = lookupTimeout;
    this.problems = problems;
    this.meters = meters;
  }

  @Override
  public int getOrder() {
    return -50; // WebFilterChainProxy(-100) 뒤, UserTypeAllowlistFilter(0) 앞
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (!path.startsWith("/api/") || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
      return chain.filter(exchange);
    }
    return exchange
        .getPrincipal()
        .filter(p -> p instanceof JwtAuthenticationToken t && t.isAuthenticated())
        .map(p -> (JwtAuthenticationToken) p)
        .flatMap(
            t -> {
              Jwt jwt = t.getToken();
              String iss = jwt.getClaimAsString("iss");
              // 주체 = 발급자 규칙이 정한 주체(인증 이름). Keycloak 직원은 sub(내부 UUID)가 아니라 pmart_operator_id —
              // 차단 사건의 subject 와 같은 값이다(2026-11-11 LAN: sub 로 찾아 막지 못했다)
              String sub = t.getName();
              if (iss == null || sub == null) {
                return Mono.just(Boolean.FALSE); // 발급자 규칙이 이미 막는다 — 여기서는 찾을 키가 없다
              }
              return blocklist
                  .blockedBefore(iss, sub)
                  .timeout(lookupTimeout)
                  .map(before -> isBlocked(jwt.getIssuedAt(), before))
                  .doOnNext(
                      blocked -> {
                        if (blocked) {
                          count("blocked");
                          log.info(
                              "subject blocked: token issued before block userType={} issuer={}",
                              UserTypeAllowlistFilter.typeOf(t),
                              iss);
                        }
                      })
                  .onErrorResume(
                      e -> {
                        count("lookup_failed");
                        warnRarely(e);
                        return Mono.just(Boolean.FALSE);
                      });
            })
        .defaultIfEmpty(Boolean.FALSE)
        .flatMap(
            blocked -> {
              if (!blocked) {
                return chain.filter(exchange);
              }
              exchange
                  .getResponse()
                  .getHeaders()
                  .set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
              return problems.write(
                  exchange,
                  HttpStatus.UNAUTHORIZED,
                  "SUBJECT_BLOCKED",
                  "사용이 중지된 계정의 토큰입니다. 다시 로그인하세요.");
            });
  }

  /** iat 가 없으면 언제 발급됐는지 알 수 없다 → 항목이 있으면 막는다. */
  static boolean isBlocked(@Nullable Instant issuedAt, long blockedBeforeEpochSecond) {
    return issuedAt == null || issuedAt.getEpochSecond() <= blockedBeforeEpochSecond;
  }

  private void count(String result) {
    if (meters != null) {
      Counter.builder("pmart.gateway.subject.block")
          .description("주체 차단 확인 결과(ADR-0054 ⑤) — blocked·lookup_failed")
          .tag("result", result)
          .register(meters)
          .increment();
    }
  }

  private void warnRarely(Throwable e) {
    long now = System.currentTimeMillis();
    long last = lastWarn.get();
    if (now - last >= WARN_EVERY_MS && lastWarn.compareAndSet(last, now)) {
      log.warn(
          "subject blocklist lookup failed — passing requests (fail-open): {}",
          e.getClass().getSimpleName());
    }
  }
}
