package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * 게이트웨이 발급자 표(ADR-0054): 실제로 서명한 토큰으로 iss 선택·종류 짝·aud·주체 클레임을 확인한다. 셋 다 같은 시험 키(기본 디코더)로 서명을 확인하고,
 * 발급자 구분은 iss 와 규칙으로만 한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "pmart.security.issuers[0].id=customer",
      "pmart.security.issuers[0].issuer-uri=http://auth-service:8501",
      "pmart.security.issuers[0].user-types=CUSTOMER,GUEST,SERVICE",
      "pmart.security.issuers[1].id=staff",
      "pmart.security.issuers[1].issuer-uri=http://keycloak:8080/realms/pmart-staff",
      "pmart.security.issuers[1].audiences=pmart-api",
      "pmart.security.issuers[1].user-types=OPERATOR",
      "pmart.security.issuers[1].subject-claim=pmart_operator_id",
      "pmart.security.issuers[1].roles-claim=realm_access.roles",
      "pmart.security.issuers[2].id=rider",
      "pmart.security.issuers[2].issuer-uri=http://rider-auth-service:8510",
      "pmart.security.issuers[2].user-types=RIDER"
    })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {
          "pmart.gateway.upstream.order-service",
          "pmart.gateway.upstream.delivery-execution-service"
        }))
class IssuerTableGatewayTest {

  static final RSAKey KEY = key();

  @TestConfiguration
  static class Keys {
    @Bean
    @Primary
    ReactiveJwtDecoder testDecoder() throws JOSEException {
      return NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
    }
  }

  @Autowired WebTestClient client;

  @InjectWireMock("upstream")
  WireMockServer upstream;

  @BeforeEach
  void stub() {
    upstream.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));
  }

  @Test
  void customerTokenFromAuthServicePasses() throws Exception {
    call("/api/v1/orders", "http://auth-service:8501", Map.of("sub", "CU-1", "typ", "CUSTOMER"))
        .expectStatus()
        .isOk();
  }

  @Test
  void riderTokenFromRiderIssuerPasses() throws Exception {
    call(
            "/api/v1/deliveries/D1",
            "http://rider-auth-service:8510",
            Map.of("sub", "RD-1", "typ", "RIDER"))
        .expectStatus()
        .isOk();
  }

  @Test
  void unknownIssuerIsUnauthorized() throws Exception {
    call("/api/v1/orders", "http://evil.example", Map.of("sub", "X", "typ", "CUSTOMER"))
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void authServiceCannotIssueRider() throws Exception {
    call("/api/v1/deliveries/D1", "http://auth-service:8501", Map.of("sub", "X", "typ", "RIDER"))
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void serviceTokenStillForbiddenOnExternalPaths() throws Exception {
    call("/api/v1/orders", "http://auth-service:8501", Map.of("sub", "svc", "typ", "SERVICE"))
        .expectStatus()
        .isForbidden();
  }

  @Test
  void keycloakStaffTokenWithAudiencePasses() throws Exception {
    call(
            "/api/v1/orders",
            "http://keycloak:8080/realms/pmart-staff",
            Map.of(
                "sub",
                "uuid",
                "pmart_operator_id",
                "claude-dev",
                "aud",
                List.of("pmart-api"),
                "realm_access",
                Map.of("roles", List.of("OPERATOR_ADMIN"))))
        .expectStatus()
        .isOk();
  }

  @Test
  void keycloakStaffTokenWithoutAudienceIsUnauthorized() throws Exception {
    call(
            "/api/v1/orders",
            "http://keycloak:8080/realms/pmart-staff",
            Map.of("sub", "uuid", "pmart_operator_id", "claude-dev"))
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void keycloakStaffTokenClaimingServiceIsUnauthorized() throws Exception {
    call(
            "/api/v1/orders",
            "http://keycloak:8080/realms/pmart-staff",
            Map.of(
                "sub", "uuid",
                "pmart_operator_id", "x",
                "aud", List.of("pmart-api"),
                "typ", "SERVICE"))
        .expectStatus()
        .isUnauthorized();
  }

  WebTestClient.ResponseSpec call(String path, String issuer, Map<String, Object> claims)
      throws JOSEException {
    return client
        .get()
        .uri(path)
        .header("Authorization", "Bearer " + sign(issuer, claims))
        .exchange();
  }

  static String sign(String issuer, Map<String, Object> claims) throws JOSEException {
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
    return jwt.serialize();
  }

  static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("gw-issuer-test").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
