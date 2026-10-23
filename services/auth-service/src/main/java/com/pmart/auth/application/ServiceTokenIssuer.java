package com.pmart.auth.application;

import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 서비스 신원 토큰 발급(client_credentials, ADR-0011). 등록된 서비스 클라이언트의 secret 을 해시로 검증하고 {@code typ=SERVICE}
 * JWT 를 짧은 수명으로 발급한다. 저장 상태 없음 — 발급 기록이 필요해지면 보안 기록(90일)에 추가한다.
 */
@Service
public class ServiceTokenIssuer {

  public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenIssuer.class);

  /** 미등록 클라이언트도 같은 비용(bcrypt 10)으로 비교해 응답 시간으로 등록 여부가 드러나지 않게 한다. 값 자체는 의미 없다. */
  private static final String DUMMY_HASH =
      "{bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG";

  private final Map<String, AuthProperties.ServiceClient> clients;
  private final PasswordEncoder encoder;
  private final JwtEncoder jwtEncoder;
  private final SigningKeyRing keys;
  private final String issuer;
  private final Duration ttl;
  private final Clock clock;

  public ServiceTokenIssuer(
      AuthProperties properties,
      PasswordEncoder encoder,
      JwtEncoder jwtEncoder,
      SigningKeyRing keys,
      Clock clock) {
    this.clients =
        properties.serviceClients().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    AuthProperties.ServiceClient::clientId, Function.identity()));
    this.encoder = encoder;
    this.jwtEncoder = jwtEncoder;
    this.keys = keys;
    this.issuer = properties.issuer();
    this.ttl = properties.serviceToken().ttl();
    this.clock = clock;
  }

  public record IssuedToken(String tokenValue, long expiresInSeconds) {}

  /** 클라이언트 인증 실패(미등록·secret 불일치). 어느 쪽인지 호출자에게 구분해 알리지 않는다. */
  public static class InvalidClientException extends RuntimeException {
    public InvalidClientException() {
      super("invalid_client");
    }
  }

  public static class UnsupportedGrantTypeException extends RuntimeException {
    public UnsupportedGrantTypeException() {
      super("unsupported_grant_type");
    }
  }

  public IssuedToken issue(String clientId, String clientSecret, String grantType) {
    AuthProperties.ServiceClient client = clients.get(clientId);
    boolean secretMatches =
        encoder.matches(clientSecret, client == null ? DUMMY_HASH : client.secretHash());
    if (client == null || !secretMatches) {
      log.warn("서비스 토큰 발급 거부: clientId={}", clientId);
      throw new InvalidClientException();
    }
    if (!GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
      throw new UnsupportedGrantTypeException();
    }
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(clientId)
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString())
            .claim("typ", "SERVICE")
            .claim("src", clientId)
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKey().getKeyID()).build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    log.debug("서비스 토큰 발급: clientId={}, ttl={}", clientId, ttl);
    return new IssuedToken(token, ttl.toSeconds());
  }
}
