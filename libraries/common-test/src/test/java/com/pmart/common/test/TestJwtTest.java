package com.pmart.common.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class TestJwtTest {

  @Test
  void tokensCarryPmartClaimsAndVerifyWithTheTestDecoder() {
    Jwt jwt =
        TestJwt.decoder().decode(TestJwt.operator("op-1", List.of("MD_LEAD"), List.of("MFC-01")));

    assertThat(jwt.getSubject()).isEqualTo("op-1");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("OPERATOR");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MD_LEAD");
    assertThat(jwt.getClaimAsStringList("centers")).containsExactly("MFC-01");
    assertThat(TestJwt.decoder().decode(TestJwt.customer("c-1")).getClaimAsString("typ"))
        .isEqualTo("CUSTOMER");
    assertThat(TestJwt.decoder().decode(TestJwt.service("order-service")).getClaimAsString("src"))
        .isEqualTo("order-service");
  }

  @Test
  void tamperedTokenIsRejected() {
    String token = TestJwt.customer("c-1");
    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    assertThatThrownBy(() -> TestJwt.decoder().decode(tampered)).isInstanceOf(JwtException.class);
  }
}
