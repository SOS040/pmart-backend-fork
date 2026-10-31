package com.pmart.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서비스 간 호출(internal-paths)의 호출자를 서비스 주체로 채운다(ADR-0026 — 서비스 간 인증 없음).
 *
 * <p>내부 경로에 토큰 없이 온 요청은 {@code X-Source-System}(호출 서비스가 적는 이름, 자기 신고)을 주체로 하는 TYPE_SERVICE 인증을 만든다.
 * 신뢰 경계는 네트워크다: 서비스는 private subnet 에만 있고 게이트웨이가 {@code /internal/**} 를 막는다. 그래서 이 이름은 호출자 식별(멱등
 * 기록·로그)용이지 권한 증명이 아니다.
 *
 * <p>사용자 토큰(고객·운영자·비회원)이 붙어 오면 JWT 인증이 먼저 되므로 여기서 바꾸지 않는다 — 내부 경로는 TYPE_SERVICE 만 받으므로 403.
 */
public class InternalCallerFilter extends OncePerRequestFilter {

  public static final String SOURCE_HEADER = "X-Source-System";
  static final String UNKNOWN_CALLER = "internal";

  private final RequestMatcher internal;

  public InternalCallerFilter(List<String> internalPaths) {
    this.internal =
        new OrRequestMatcher(
            internalPaths.stream()
                .map(p -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(p))
                .toList());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication current = SecurityContextHolder.getContext().getAuthentication();
    if ((current == null || current instanceof AnonymousAuthenticationToken)
        && internal.matches(request)) {
      String source = request.getHeader(SOURCE_HEADER);
      String caller = source == null || source.isBlank() ? UNKNOWN_CALLER : source.trim();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new InternalCallerAuthentication(
                  new PmartPrincipal(
                      caller, PmartPrincipal.PrincipalType.SERVICE, List.of(), List.of(), caller)));
    }
    chain.doFilter(request, response);
  }

  /** 토큰 없는 내부 호출의 인증(TYPE_SERVICE). */
  public static final class InternalCallerAuthentication extends AbstractAuthenticationToken {

    private final PmartPrincipal principal;

    InternalCallerAuthentication(PmartPrincipal principal) {
      super(List.of(new SimpleGrantedAuthority("TYPE_SERVICE")));
      this.principal = principal;
      setAuthenticated(true);
    }

    @Override
    public @Nullable Object getCredentials() {
      return null;
    }

    @Override
    public PmartPrincipal getPrincipal() {
      return principal;
    }
  }
}
