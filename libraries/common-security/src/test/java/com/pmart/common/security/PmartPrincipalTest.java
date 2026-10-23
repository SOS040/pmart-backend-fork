package com.pmart.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class PmartPrincipalTest {

  private static Jwt jwt() {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .subject("op-7")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("typ", "OPERATOR")
        .claim("roles", List.of("CENTER_MANAGER", "QUALITY_APPROVER"))
        .claim("centers", List.of("MFC-BSN-01", "MFC-BSN-02"))
        .claim("scope", "read write")
        .claim("src", "backoffice-bff")
        .build();
  }

  @Test
  void extractsTypeRolesAndCenterScope() {
    PmartPrincipal p = PmartPrincipal.fromJwt(jwt());
    assertThat(p.subject()).isEqualTo("op-7");
    assertThat(p.type()).isEqualTo(PmartPrincipal.PrincipalType.OPERATOR);
    assertThat(p.hasRole("CENTER_MANAGER")).isTrue();
    assertThat(p.canAccessCenter("MFC-BSN-02")).isTrue();
    assertThat(p.canAccessCenter("MFC-DGU-01")).isFalse();
    assertThat(p.sourceSystem()).isEqualTo("backoffice-bff");
    assertThat(p.isService()).isFalse();
  }

  @Test
  void converterMapsScopesRolesAndType() {
    var auth = new PmartJwtAuthenticationConverter().convert(jwt());
    List<String> names =
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    assertThat(names)
        .contains(
            "SCOPE_read",
            "SCOPE_write",
            "ROLE_CENTER_MANAGER",
            "ROLE_QUALITY_APPROVER",
            "TYPE_OPERATOR");
  }

  @Test
  void unknownTypeDoesNotBreak() {
    Jwt j =
        Jwt.withTokenValue("t").header("alg", "none").subject("x").claim("typ", "ALIEN").build();
    assertThat(PmartPrincipal.fromJwt(j).type()).isEqualTo(PmartPrincipal.PrincipalType.UNKNOWN);
    assertThat(PmartPrincipal.fromJwt(j).roles()).isEmpty();
  }
}
