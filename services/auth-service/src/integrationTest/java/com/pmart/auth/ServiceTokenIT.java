package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.security.ServiceTokenProvider;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

/**
 * 서비스 신원 토큰 E2E(ADR-0011): 실제 HTTP 로 발급 → JWKS 로 검증 → 내부 API 통과까지. 호출 측은 common-security 의 {@link
 * ServiceTokenProvider} 를 그대로 쓴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.auth.service-token.ttl=PT2M",
      // secret: pmart-local
      "pmart.auth.service-clients[0].client-id=order-service",
      "pmart.auth.service-clients[0].secret-hash={bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG"
    })
class ServiceTokenIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private final RestClient http =
      RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();

  private ServiceTokenProvider provider(String clientId, String secret) {
    return new ServiceTokenProvider(
        RestClient.create(),
        url("/api/v1/internal/service-tokens"),
        clientId,
        secret,
        Duration.ofSeconds(30),
        Clock.systemUTC());
  }

  @Test
  void issuedTokenVerifiesAgainstJwksAndPassesInternalSecurity() {
    String token = provider("order-service", "pmart-local").token();

    Jwt jwt = NimbusJwtDecoder.withJwkSetUri(url("/api/v1/internal/jwks")).build().decode(token);
    assertThat(jwt.getSubject()).isEqualTo("order-service");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("SERVICE");
    assertThat(jwt.getClaimAsString("src")).isEqualTo("order-service");
    assertThat(jwt.getIssuer()).hasToString("http://auth-service.test");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(2));

    // /api/v1/internal/** 는 TYPE_SERVICE. 보안을 통과하면 아직 구현 전인 스텁의 501.
    assertThat(internalCall(token).getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    assertThat(internalCall(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void jwksIsPublicAndHasNoPrivateParts() {
    @SuppressWarnings("unchecked")
    Map<String, Object> jwks =
        http.get().uri(url("/api/v1/internal/jwks")).retrieve().body(Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
    assertThat(keys).hasSize(1);
    assertThat(keys.getFirst()).containsKeys("kid", "n", "e").doesNotContainKeys("d", "p", "q");
    assertThat(keys.getFirst()).containsEntry("alg", "RS256").containsEntry("use", "sig");
  }

  @Test
  void wrongSecretOrUnknownClientIsInvalidClient() {
    for (String[] creds : new String[][] {{"order-service", "wrong"}, {"nobody", "pmart-local"}}) {
      ResponseEntity<Map> res = tokenRequest(creds[0], creds[1], "client_credentials");
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(res.getBody()).containsEntry("error", "invalid_client");
      assertThat(res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
    }
  }

  @Test
  void unsupportedGrantTypeIsRejected() {
    ResponseEntity<Map> res = tokenRequest("order-service", "pmart-local", "password");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody()).containsEntry("error", "unsupported_grant_type");
  }

  @Test
  void userTokenFromAnotherIssuerIsRejected() {
    // 다른 키로 서명된 토큰(위조)은 401
    assertThat(
            internalCall("eyJhbGciOiJSUzI1NiIsImtpZCI6IngifQ.eyJzdWIiOiJ4In0.c2ln").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private ResponseEntity<Void> internalCall(String token) {
    var spec =
        http.get()
            .uri(url("/api/v1/internal/principals/op-1/grants"))
            .accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return spec.retrieve().toBodilessEntity();
  }

  @SuppressWarnings("rawtypes")
  private ResponseEntity<Map> tokenRequest(String clientId, String secret, String grantType) {
    return http.post()
        .uri(url("/api/v1/internal/service-tokens"))
        .headers(h -> h.setBasicAuth(clientId, secret))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("grant_type=" + grantType)
        .retrieve()
        .toEntity(Map.class);
  }
}
