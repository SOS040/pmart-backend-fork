package com.pmart.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class UserTypeAllowlistFilterTest {

  final UserTypeAllowlist list =
      UserTypeAllowlist.parse(List.of("POST /api/v1/deliveries/{id}/completions RIDER"));
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  UserTypeAllowlistFilter filter(UserTypeAllowlistFilter.Mode mode) {
    return new UserTypeAllowlistFilter(
        list, mode, new ProblemResponseWriter(JsonMapper.builder().build()), meters);
  }

  static ServerWebExchange exchange(String type) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("s")
            .claim("typ", type)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of());
    return MockServerWebExchange.builder(
            MockServerHttpRequest.post("/api/v1/deliveries/D1/completions"))
        .principal(auth)
        .build();
  }

  static boolean run(UserTypeAllowlistFilter f, ServerWebExchange ex) {
    AtomicBoolean passed = new AtomicBoolean();
    WebFilterChain chain =
        e -> {
          passed.set(true);
          return Mono.empty();
        };
    f.filter(ex, chain).block();
    return passed.get();
  }

  @Test
  void recordModeLetsMismatchThroughAndCounts() {
    ServerWebExchange ex = exchange("CUSTOMER");
    assertThat(run(filter(UserTypeAllowlistFilter.Mode.RECORD), ex)).isTrue();
    assertThat(
            meters
                .find("pmart.gateway.user.type.allowlist")
                .tag("verdict", "TYPE_NOT_ALLOWED")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void enforceModeBlocksMismatch() {
    ServerWebExchange ex = exchange("CUSTOMER");
    assertThat(run(filter(UserTypeAllowlistFilter.Mode.ENFORCE), ex)).isFalse();
    assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void enforceModeAllowsListedType() {
    assertThat(run(filter(UserTypeAllowlistFilter.Mode.ENFORCE), exchange("RIDER"))).isTrue();
  }

  @Test
  void unauthenticatedRequestIsLeftToSecurityChain() {
    ServerWebExchange ex =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/deliveries/D1/completions"));
    assertThat(run(filter(UserTypeAllowlistFilter.Mode.ENFORCE), ex)).isTrue();
  }
}
