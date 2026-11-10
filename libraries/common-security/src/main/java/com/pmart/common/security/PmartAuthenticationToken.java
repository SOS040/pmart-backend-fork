package com.pmart.common.security;

import com.pmart.common.jwt.IssuerPolicy;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * 발급자 표(ADR-0054)로 인증된 토큰. 원래 {@link Jwt} 와 함께 <b>발급자 규칙으로 정한</b> 호출 주체를 들고 있다 — 사용자 종류는 토큰 안 문자열이
 * 아니라 발급자에서 왔다. {@link PmartPrincipal#current()} 는 이 값을 먼저 쓴다.
 */
public class PmartAuthenticationToken extends JwtAuthenticationToken {

  private final PmartPrincipal pmartPrincipal;
  private final String issuerId;

  public PmartAuthenticationToken(
      Jwt jwt,
      Collection<? extends GrantedAuthority> authorities,
      PmartPrincipal principal,
      String issuerId) {
    super(jwt, authorities, principal.subject());
    this.pmartPrincipal = principal;
    this.issuerId = issuerId;
  }

  public PmartPrincipal pmartPrincipal() {
    return pmartPrincipal;
  }

  /** 발급자 표의 id(예: customer, staff, rider). */
  public String issuerId() {
    return issuerId;
  }

  /** 발급자 규칙 → 권한: {@code SCOPE_x}(Spring 기본), {@code ROLE_x}, {@code TYPE_x}. */
  public static Converter<Jwt, AbstractAuthenticationToken> converter(IssuerPolicy policy) {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    return jwt -> {
      IssuerPolicy.Claims c = policy.claims(jwt);
      PmartPrincipal p =
          new PmartPrincipal(
              c.subject(),
              PmartPrincipal.PrincipalType.valueOf(c.userType()),
              c.roles(),
              c.centers(),
              c.source());
      Collection<GrantedAuthority> authorities = new ArrayList<>();
      Collection<GrantedAuthority> fromScopes = scopes.convert(jwt);
      if (fromScopes != null) {
        authorities.addAll(fromScopes);
      }
      p.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
      authorities.add(new SimpleGrantedAuthority("TYPE_" + p.type().name()));
      return new PmartAuthenticationToken(jwt, authorities, p, policy.id());
    };
  }
}
