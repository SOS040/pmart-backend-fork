package com.pmart.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * JWT → 권한.
 *
 * <ul>
 *   <li>{@code scope}/{@code scp} → {@code SCOPE_x} (Spring 기본)
 *   <li>{@code roles} → {@code ROLE_x}
 *   <li>{@code typ} → {@code TYPE_x} (CUSTOMER/RIDER/OPERATOR/SERVICE). {@code /internal/**} 는
 *       TYPE_SERVICE 필요.
 * </ul>
 */
public class PmartJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    Collection<GrantedAuthority> fromScopes = scopes.convert(jwt);
    if (fromScopes != null) {
      authorities.addAll(fromScopes);
    }
    List<String> roles = jwt.getClaimAsStringList(PmartPrincipal.CLAIM_ROLES);
    if (roles != null) {
      roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
    }
    String typ = jwt.getClaimAsString(PmartPrincipal.CLAIM_TYPE);
    if (typ != null && !typ.isBlank()) {
      authorities.add(new SimpleGrantedAuthority("TYPE_" + typ));
    }
    return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
  }
}
