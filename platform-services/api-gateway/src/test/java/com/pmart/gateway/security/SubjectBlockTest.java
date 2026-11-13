package com.pmart.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 주체 차단(ADR-0054 ⑤) — 판정 경계(초 단위)·Redis 장애 통과·사건 해석과 남길 기간. Redis·Kafka 끝까지는 SubjectBlocklistIT.
 */
class SubjectBlockTest {

  static final Instant NOW = Instant.parse("2026-11-11T03:00:00.700Z");

  @Test
  void tokenIssuedInTheSameSecondOrEarlierIsBlocked() {
    long blockedAt = NOW.getEpochSecond();
    assertThat(SubjectBlockFilter.isBlocked(NOW.minusSeconds(1), blockedAt)).isTrue();
    assertThat(SubjectBlockFilter.isBlocked(Instant.parse("2026-11-11T03:00:00.100Z"), blockedAt))
        .isTrue();
    assertThat(SubjectBlockFilter.isBlocked(NOW.plusSeconds(1), blockedAt)).isFalse();
    assertThat(SubjectBlockFilter.isBlocked(null, blockedAt)).isTrue(); // iat 없음
  }

  @Test
  void blockedSubjectGets401AndOthersPass() {
    FakeBlocklist list = new FakeBlocklist();
    list.entry = Mono.just(NOW.getEpochSecond());
    assertThat(run(list, NOW.minusSeconds(30))).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(run(list, NOW.plusSeconds(30))).isNull(); // 체인으로 넘김(상태 안 씀)
    list.entry = Mono.empty();
    assertThat(run(list, NOW.minusSeconds(30))).isNull();
  }

  /**
   * Keycloak 직원: sub 는 내부 UUID, 발급자 규칙의 주체는 pmart_operator_id — 차단 사건의 subject 와 같은 값으로
   * 찾는다(2026-11-11 LAN 결함).
   */
  @Test
  void looksUpIssuerTableSubjectNotRawSub() {
    FakeBlocklist list = new FakeBlocklist();
    list.entry = Mono.just(NOW.getEpochSecond());
    String iss = "http://192.168.0.101:8180/realms/pmart-staff";
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("6f1c2e9a-0000-4000-8000-000000000001")
            .claim("iss", iss)
            .claim("pmart_operator_id", "OP-1")
            .issuedAt(NOW.minusSeconds(30))
            .expiresAt(NOW.plusSeconds(900))
            .build();
    IssuedToken staff = new IssuedToken(jwt, List.of(), "OP-1", "OPERATOR", "staff");
    assertThat(run(list, staff)).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(list.lookedUp).isEqualTo(iss + "|OP-1");
  }

  @Test
  void redisFailureAndTimeoutPassRequests() {
    FakeBlocklist list = new FakeBlocklist();
    list.entry = Mono.error(new IllegalStateException("redis down"));
    assertThat(run(list, NOW.minusSeconds(30))).isNull();
    list.entry = Mono.never();
    assertThat(run(list, NOW.minusSeconds(30))).isNull();
  }

  /** 로그아웃(2026-11-13): 그 세션(sid)의 토큰만 막고, 같은 사람의 다른 세션은 통과. */
  @Test
  void loggedOutSessionIsBlockedOtherSessionsPass() {
    FakeBlocklist list = new FakeBlocklist();
    list.sessionEntry = Mono.just(NOW.getEpochSecond());
    assertThat(run(list, withSid("ESES-1"))).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(list.sessionLookedUp).isEqualTo("http://employee-auth-service:8511|ESES-1");
    list.sessionEntry = Mono.empty(); // 다른 세션(항목 없음)
    assertThat(run(list, withSid("ESES-2"))).isNull();
  }

  @Test
  void sessionEventIsRecordedPerSession() {
    FakeBlocklist list = new FakeBlocklist();
    SubjectBlockEvents events =
        new SubjectBlockEvents(
            list, Duration.ofHours(2), Duration.ofSeconds(1), Clock.fixed(NOW, ZoneOffset.UTC));
    events.accept(
        message(
            """
            {"eventId":"e-2","eventType":"SubjectBlocked","payload":{"issuer":"http://employee-auth-service:8511",
             "subject":"OP-1","sessionId":"ESES-1","userType":"OPERATOR","blockedBefore":"%s","reason":"LOGOUT"}}
            """
                .formatted(NOW.toString())));
    assertThat(list.recorded).isEmpty();
    assertThat(list.sessions).hasSize(1);
    assertThat(list.sessions.get(0).subject()).isEqualTo("ESES-1");
  }

  private static JwtAuthenticationToken withSid(String sid) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("OP-1")
            .claim("iss", "http://employee-auth-service:8511")
            .claim("sid", sid)
            .issuedAt(NOW.minusSeconds(30))
            .expiresAt(NOW.plusSeconds(900))
            .build();
    return new JwtAuthenticationToken(jwt, List.of());
  }

  @Test
  void eventIsRecordedWithTtlFromBlockTime() {
    FakeBlocklist list = new FakeBlocklist();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    SubjectBlockEvents events =
        new SubjectBlockEvents(list, Duration.ofHours(2), Duration.ofSeconds(1), clock);
    events.accept(message(envelope("SubjectBlocked", NOW.minusSeconds(600).toString())));
    assertThat(list.recorded).hasSize(1);
    assertThat(list.recorded.get(0).ttl()).isEqualTo(Duration.ofMinutes(110));
    assertThat(list.recorded.get(0).issuer()).isEqualTo("http://auth-service:8501");

    // 다른 종류 사건은 무시, 깨진 사건은 IllegalArgumentException(재시도 없이 DLT)
    events.accept(message(envelope("SomethingElse", NOW.toString())));
    assertThat(list.recorded).hasSize(1);
    assertThatThrownBy(() -> events.accept(message(envelope("SubjectBlocked", "어제"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> events.accept(message("{not json")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- 도움

  private static HttpStatus run(FakeBlocklist list, Instant issuedAt) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("OP-1")
            .claim("iss", "http://auth-service:8501")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(900))
            .build();
    return run(list, new JwtAuthenticationToken(jwt, List.of()));
  }

  private static HttpStatus run(FakeBlocklist list, JwtAuthenticationToken auth) {
    SubjectBlockFilter filter =
        new SubjectBlockFilter(
            list,
            Duration.ofMillis(100),
            new ProblemResponseWriter(tools.jackson.databind.json.JsonMapper.builder().build()),
            null);
    MockServerWebExchange base =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/ord-1"));
    ServerWebExchange exchange = base.mutate().principal(Mono.just(auth)).build();
    AtomicBoolean passed = new AtomicBoolean();
    WebFilterChain chain =
        e -> {
          passed.set(true);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block(Duration.ofSeconds(5));
    if (passed.get()) {
      return null;
    }
    return HttpStatus.resolve(base.getResponse().getStatusCode().value());
  }

  private static org.springframework.messaging.Message<byte[]> message(String json) {
    return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
  }

  private static String envelope(String type, String blockedBefore) {
    return """
        {"eventId":"e-1","eventType":"%s","payload":{"issuer":"http://auth-service:8501",
         "subject":"OP-1","userType":"OPERATOR","blockedBefore":"%s","reason":"ACCOUNT_DISABLED"}}
        """
        .formatted(type, blockedBefore);
  }

  record Recorded(String issuer, String subject, Instant blockedBefore, Duration ttl) {}

  static final class FakeBlocklist extends SubjectBlocklist {
    Mono<Long> entry = Mono.empty();
    String lookedUp;
    final List<Recorded> recorded = new ArrayList<>();

    FakeBlocklist() {
      super(null);
    }

    @Override
    public Mono<Long> blockedBefore(String issuer, String subject) {
      lookedUp = issuer + "|" + subject;
      return entry;
    }

    Mono<Long> sessionEntry = Mono.empty();
    String sessionLookedUp;
    final List<Recorded> sessions = new ArrayList<>();

    @Override
    public Mono<Long> sessionBlockedBefore(String issuer, String sessionId) {
      sessionLookedUp = issuer + "|" + sessionId;
      return sessionEntry;
    }

    @Override
    public Mono<Boolean> record(
        String issuer, String subject, Instant blockedBefore, Duration ttl) {
      recorded.add(new Recorded(issuer, subject, blockedBefore, ttl));
      return Mono.just(true);
    }

    @Override
    public Mono<Boolean> recordSession(
        String issuer, String sessionId, Instant blockedBefore, Duration ttl) {
      sessions.add(new Recorded(issuer, sessionId, blockedBefore, ttl));
      return Mono.just(true);
    }
  }
}
