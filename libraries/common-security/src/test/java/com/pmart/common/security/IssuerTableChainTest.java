package com.pmart.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급자 표(ADR-0054): 실제로 서명한 토큰으로 iss 선택·종류 짝·Keycloak 형태 클레임(주체·역할 경로)을 확인한다. 발급자 셋 모두 앱 디코더(같은 시험
 * 키)로 서명을 확인한다 — 발급자 구분은 iss 와 규칙으로만.
 */
@SpringBootTest(
    classes = IssuerTableChainTest.App.class,
    properties = {
      "spring.application.name=issuer-test",
      "spring.main.banner-mode=off",
      "pmart.security.issuers[0].id=customer",
      "pmart.security.issuers[0].issuer-uri=http://auth-service:8501",
      "pmart.security.issuers[0].user-types=CUSTOMER,GUEST,SERVICE",
      "pmart.security.issuers[1].id=staff",
      "pmart.security.issuers[1].issuer-uri=http://keycloak:8080/realms/pmart-staff",
      "pmart.security.issuers[1].audiences=pmart-api",
      "pmart.security.issuers[1].user-types=OPERATOR",
      "pmart.security.issuers[1].type-claim=",
      "pmart.security.issuers[1].subject-claim=pmart_operator_id",
      "pmart.security.issuers[1].roles-claim=realm_access.roles",
      "pmart.security.issuers[2].id=rider",
      "pmart.security.issuers[2].issuer-uri=http://rider-auth-service:8510",
      "pmart.security.issuers[2].user-types=RIDER"
    })
@AutoConfigureMockMvc
class IssuerTableChainTest {

  static final RSAKey KEY = key();
  static final String CUSTOMER_ISS = "http://auth-service:8501";
  static final String STAFF_ISS = "http://keycloak:8080/realms/pmart-staff";
  static final String RIDER_ISS = "http://rider-auth-service:8510";

  @SpringBootApplication
  @EnableMethodSecurity
  static class App {
    @Bean
    JwtDecoder jwtDecoder() throws JOSEException {
      return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
    }

    @RestController
    static class Api {
      @GetMapping("/api/v1/deliveries/D1/completions")
      @PreAuthorize("hasAuthority('TYPE_RIDER')")
      String complete() {
        return PmartPrincipal.current().map(p -> p.type() + ":" + p.subject()).orElse("none");
      }

      @GetMapping("/api/v1/assistant/ask")
      @PreAuthorize("hasAuthority('TYPE_OPERATOR') and hasRole('MFC_SUPERVISOR')")
      String ask() {
        return PmartPrincipal.current()
            .map(p -> p.type() + ":" + p.subject() + ":" + p.roles() + ":" + p.centerScope())
            .orElse("none");
      }

      @GetMapping("/api/v1/orders")
      String orders() {
        return PmartPrincipal.current().map(p -> p.type() + ":" + p.subject()).orElse("none");
      }
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void riderTokenFromRiderIssuerPasses() throws Exception {
    mvc.perform(
            get("/api/v1/deliveries/D1/completions")
                .header("Authorization", bearer(RIDER_ISS, Map.of("sub", "RD-1", "typ", "RIDER"))))
        .andExpect(status().isOk())
        .andExpect(content().string("RIDER:RD-1"));
  }

  @Test
  void customerTokenOnRiderApiIsForbidden() throws Exception {
    mvc.perform(
            get("/api/v1/deliveries/D1/completions")
                .header(
                    "Authorization",
                    bearer(CUSTOMER_ISS, Map.of("sub", "CU-1", "typ", "CUSTOMER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void customerIssuerCannotClaimRider() throws Exception {
    // auth-service 가 서명했어도 라이더라고 주장하면 무효(짝 위반)
    mvc.perform(
            get("/api/v1/deliveries/D1/completions")
                .header(
                    "Authorization", bearer(CUSTOMER_ISS, Map.of("sub", "CU-1", "typ", "RIDER"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownIssuerIsUnauthorized() throws Exception {
    mvc.perform(
            get("/api/v1/orders")
                .header(
                    "Authorization",
                    bearer("http://evil.example", Map.of("sub", "X", "typ", "CUSTOMER"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void keycloakStaffTokenMapsToOperatorWithOperatorIdAndRealmRoles() throws Exception {
    Map<String, Object> claims =
        Map.of(
            "sub",
            "6f1c-uuid",
            "typ",
            "Bearer",
            "pmart_operator_id",
            "claude-mfc-sup",
            "aud",
            List.of("pmart-api"),
            "realm_access",
            Map.of("roles", List.of("MFC_SUPERVISOR")),
            "centers",
            List.of("MFC-SEED-01"));
    mvc.perform(get("/api/v1/assistant/ask").header("Authorization", bearer(STAFF_ISS, claims)))
        .andExpect(status().isOk())
        .andExpect(content().string("OPERATOR:claude-mfc-sup:[MFC_SUPERVISOR]:[MFC-SEED-01]"));
  }

  @Test
  void staffTokenWithoutAudienceIsUnauthorized() throws Exception {
    Map<String, Object> claims =
        Map.of(
            "sub",
            "u",
            "pmart_operator_id",
            "op-1",
            "realm_access",
            Map.of("roles", List.of("MFC_SUPERVISOR")));
    mvc.perform(get("/api/v1/assistant/ask").header("Authorization", bearer(STAFF_ISS, claims)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  // 직원 발급자는 종류 클레임을 보지 않는다(Keycloak typ=Bearer) — 고객이라고 적어도 발급자 규칙대로 OPERATOR
  void staffTokenClaimingCustomerIsStillOperator() throws Exception {
    Map<String, Object> claims =
        Map.of(
            "sub",
            "u",
            "pmart_operator_id",
            "op-1",
            "aud",
            List.of("pmart-api"),
            "typ",
            "CUSTOMER");
    mvc.perform(get("/api/v1/orders").header("Authorization", bearer(STAFF_ISS, claims)))
        .andExpect(status().isOk())
        .andExpect(content().string("OPERATOR:op-1"));
  }

  @Test
  void staffTokenWithoutOperatorIdIsUnauthorized() throws Exception {
    Map<String, Object> claims = Map.of("sub", "u", "aud", List.of("pmart-api"));
    mvc.perform(get("/api/v1/orders").header("Authorization", bearer(STAFF_ISS, claims)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void guestTokenStillLimitedToGuestPaths() throws Exception {
    mvc.perform(
            get("/api/v1/orders")
                .header(
                    "Authorization", bearer(CUSTOMER_ISS, Map.of("sub", "G-1", "typ", "GUEST"))))
        .andExpect(status().isForbidden());
  }

  static String bearer(String issuer, Map<String, Object> claims) throws JOSEException {
    Instant now = Instant.now();
    JWTClaimsSet.Builder b =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)));
    claims.forEach(b::claim);
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), b.build());
    jwt.sign(new RSASSASigner(KEY));
    return "Bearer " + jwt.serialize();
  }

  static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("issuer-test").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
