package com.pmart.common.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class IssuerPolicyTest {

  static IssuerSettings settings(String iss, List<String> types) {
    IssuerSettings s = new IssuerSettings();
    s.setId("t");
    s.setIssuerUri(iss);
    s.setUserTypes(types);
    return s;
  }

  static Jwt jwt(String iss, Map<String, Object> claims) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer(iss)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    claims.forEach(b::claim);
    return b.build();
  }

  @Test
  void typeMustBelongToIssuer() {
    IssuerPolicy p = new IssuerPolicy(settings("http://a", List.of("CUSTOMER", "GUEST")));
    assertThat(p.resolveType(jwt("http://a", Map.of("sub", "x", "typ", "CUSTOMER"))))
        .isEqualTo("CUSTOMER");
    assertThat(p.resolveType(jwt("http://a", Map.of("sub", "x", "typ", "RIDER")))).isNull();
    assertThat(p.resolveType(jwt("http://a", Map.of("sub", "x")))).isNull(); // 종류 둘 이상 + typ 없음
    assertThat(
            p.validator().validate(jwt("http://a", Map.of("sub", "x", "typ", "RIDER"))).hasErrors())
        .isTrue();
  }

  @Test
  void singleTypeIssuerWithoutTypClaimUsesThatType() {
    IssuerSettings s = settings("http://kc", List.of("OPERATOR"));
    s.setSubjectClaim("pmart_operator_id");
    s.setRolesClaim("realm_access.roles");
    IssuerPolicy p = new IssuerPolicy(s);
    Jwt t =
        jwt(
            "http://kc",
            Map.of(
                "sub", "uuid",
                "pmart_operator_id", "op-1",
                "realm_access", Map.of("roles", List.of("A", "B")),
                "centers", "MFC-1"));
    assertThat(p.validator().validate(t).hasErrors()).isFalse();
    IssuerPolicy.Claims c = p.claims(t);
    assertThat(c.subject()).isEqualTo("op-1");
    assertThat(c.userType()).isEqualTo("OPERATOR");
    assertThat(c.roles()).containsExactly("A", "B");
    assertThat(c.centers()).containsExactly("MFC-1");
  }

  @Test
  void wrongIssuerAudienceOrMissingSubjectFails() {
    IssuerSettings s = settings("http://kc", List.of("OPERATOR"));
    s.setAudiences(List.of("pmart-api"));
    IssuerPolicy p = new IssuerPolicy(s);
    assertThat(
            p.validator()
                .validate(jwt("http://other", Map.of("sub", "x", "aud", List.of("pmart-api"))))
                .hasErrors())
        .isTrue();
    assertThat(
            p.validator()
                .validate(jwt("http://kc", Map.of("sub", "x", "aud", List.of("other"))))
                .hasErrors())
        .isTrue();
    assertThat(
            p.validator()
                .validate(jwt("http://kc", Map.of("aud", List.of("pmart-api"))))
                .hasErrors())
        .isTrue();
    assertThat(
            p.validator()
                .validate(jwt("http://kc", Map.of("sub", "x", "aud", List.of("pmart-api"))))
                .hasErrors())
        .isFalse();
  }

  @Test
  void tableRejectsBadSettings() {
    assertThatThrownBy(() -> new IssuerPolicy(settings("", List.of("CUSTOMER"))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new IssuerPolicy(settings("http://a", List.of())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new IssuerPolicy(settings("http://a", List.of("ADMIN"))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                IssuerPolicy.table(
                    List.of(
                        settings("http://a", List.of("CUSTOMER")),
                        settings("http://a", List.of("RIDER")))))
        .isInstanceOf(IllegalStateException.class);
  }
}
