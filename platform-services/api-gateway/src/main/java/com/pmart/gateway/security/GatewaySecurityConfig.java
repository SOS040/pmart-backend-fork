package com.pmart.gateway.security;

import com.pmart.gateway.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * 게이트웨이 보안 체인 (security-config.md).
 *
 * <ul>
 *   <li>JWT(auth-service JWKS) 검증. 토큰 없음·무효 → 401 Problem, 권한 없음 → 403 Problem.
 *   <li>{@code /api/{v}/internal/**} 는 서비스 간 전용 — 외부에서 절대 통과하지 않는다(denyAll).
 *   <li>서비스 신원 토큰({@code typ=SERVICE})은 외부 경로에서 403 — 내부 호출 전용(ADR-0011).
 *   <li>공개 작업은 {@code pmart.gateway.public-paths} 에만(계약의 {@code security: []}).
 *   <li>Actuator 는 health/info/prometheus 만. 나머지 actuator 는 인증 필요.
 *   <li>세부 인가(역할·센터·행위 시점 검증)는 각 서비스가 한다. 게이트웨이는 "토큰이 유효한가"까지만.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

  /** 인증됐고 서비스 신원 토큰이 아닐 때만 허용. 서비스 토큰으로 외부 경로를 부르면 403. */
  static final ReactiveAuthorizationManager<AuthorizationContext> USER_TOKEN_ONLY =
      (authentication, context) ->
          authentication
              .filter(Authentication::isAuthenticated)
              .map(a -> (AuthorizationResult) new AuthorizationDecision(!isServiceToken(a)))
              .defaultIfEmpty(new AuthorizationDecision(false));

  static boolean isServiceToken(Authentication authentication) {
    return authentication.getPrincipal() instanceof Jwt jwt
        && "SERVICE".equals(jwt.getClaimAsString("typ"));
  }

  @Bean
  SecurityWebFilterChain gatewaySecurityFilterChain(
      ServerHttpSecurity http, GatewayProperties properties, ProblemResponseWriter problems) {
    ServerAuthenticationEntryPoint entryPoint =
        (exchange, ex) -> {
          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
          return problems.write(
              exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "유효한 인증 토큰이 필요합니다.");
        };
    ServerAccessDeniedHandler deniedHandler =
        (exchange, ex) ->
            problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근이 허용되지 않은 경로입니다.");

    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
        .authorizeExchange(
            exchanges -> {
              exchanges
                  .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                  .permitAll();
              exchanges.pathMatchers("/internal/**", "/api/*/internal/**").denyAll();
              for (GatewayProperties.PublicPath p : properties.publicPaths()) {
                exchanges.pathMatchers(HttpMethod.valueOf(p.method()), p.path()).permitAll();
              }
              // 서비스 신원 토큰(typ=SERVICE)은 /internal 전용(ADR-0011) — 외부 경로에서는 유효해도 거부(방어 심화)
              exchanges.anyExchange().access(USER_TOKEN_ONLY);
            })
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler))
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
    return http.build();
  }
}
