package com.pmart.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.auth.infrastructure.keys.KeyConfig;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

class ServiceTokenIssuerTest {

  private static final Instant NOW = Instant.parse("2026-10-23T01:00:00Z");

  private final PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private final AuthProperties properties =
      new AuthProperties(
          "http://auth-service:8501",
          new AuthProperties.Signing(List.of(), true),
          new AuthProperties.ServiceToken(Duration.ofMinutes(5)),
          List.of(new AuthProperties.ServiceClient("order-service", encoder.encode("s3cret"))));
  private final SigningKeyRing ring = SigningKeyRing.from(properties.signing());
  private final KeyConfig config = new KeyConfig();
  private final JwtEncoder jwtEncoder = config.jwtEncoder(ring);
  private final JwtDecoder jwtDecoder = config.jwtDecoder(ring);

  private ServiceTokenIssuer issuerAt(Instant now) {
    return new ServiceTokenIssuer(
        properties, encoder, jwtEncoder, ring, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void issuesShortLivedServiceTokenSignedWithActiveKey() {
    ServiceTokenIssuer.IssuedToken issued =
        issuerAt(Instant.now()).issue("order-service", "s3cret", "client_credentials");

    Jwt jwt = jwtDecoder.decode(issued.tokenValue());
    assertThat(issued.expiresInSeconds()).isEqualTo(300);
    assertThat(jwt.getSubject()).isEqualTo("order-service");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("SERVICE");
    assertThat(jwt.getClaimAsString("src")).isEqualTo("order-service");
    assertThat(jwt.getHeaders()).containsEntry("kid", ring.activeKey().getKeyID());
    assertThat(jwt.getId()).isNotBlank();
  }

  @Test
  void expiredTokenIsRejectedByDecoder() {
    String token =
        issuerAt(NOW).issue("order-service", "s3cret", "client_credentials").tokenValue();
    assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void wrongSecretAndUnknownClientAreIndistinguishable() {
    ServiceTokenIssuer issuer = issuerAt(NOW);
    assertThatThrownBy(() -> issuer.issue("order-service", "nope", "client_credentials"))
        .isInstanceOf(ServiceTokenIssuer.InvalidClientException.class);
    assertThatThrownBy(() -> issuer.issue("unknown-service", "s3cret", "client_credentials"))
        .isInstanceOf(ServiceTokenIssuer.InvalidClientException.class);
  }

  @Test
  void clientIsAuthenticatedBeforeGrantTypeIsChecked() {
    ServiceTokenIssuer issuer = issuerAt(NOW);
    assertThatThrownBy(() -> issuer.issue("unknown-service", "x", "password"))
        .isInstanceOf(ServiceTokenIssuer.InvalidClientException.class);
    assertThatThrownBy(() -> issuer.issue("order-service", "s3cret", "password"))
        .isInstanceOf(ServiceTokenIssuer.UnsupportedGrantTypeException.class);
  }
}
