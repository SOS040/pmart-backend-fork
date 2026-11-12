package com.pmart.employeeauth.application;

import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.keys.SigningKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * PMart 직원 토큰 발급. access token 모양은 예전 auth-service 운영자 토큰과 같다(ADR-0056 결정 3) — {@code iss} = 이 서비스,
 * {@code sub} = 운영자 ID, {@code typ} = OPERATOR, {@code aud} = pmart-api, {@code roles}, {@code
 * centers}, {@code sid}. 역할·센터는 발급 시점의 이 서비스 정본 값. refresh token 은 무작위 값이고 DB 에는 SHA-256 만. 호출자
 * 트랜잭션 안에서 쓴다.
 */
@Component
public class EmployeeTokens {

  public static final String TYPE = "OPERATOR";

  private static final SecureRandom RANDOM = new SecureRandom();

  public record Tokens(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      String sessionId) {}

  private final EmployeeAuthStore store;
  private final JwtEncoder jwtEncoder;
  private final SigningKeyRing keys;
  private final EmployeeAuthProperties props;

  public EmployeeTokens(
      EmployeeAuthStore store,
      JwtEncoder jwtEncoder,
      SigningKeyRing keys,
      EmployeeAuthProperties props) {
    this.store = store;
    this.jwtEncoder = jwtEncoder;
    this.keys = keys;
    this.props = props;
  }

  /** access + refresh. 둘 다 세션 만료(sessionExpires)를 넘지 않는다. */
  public Tokens issue(
      String operatorId,
      List<String> roles,
      List<String> centers,
      String sessionId,
      Instant sessionExpires,
      Instant now) {
    Instant accessExpires = min(now.plus(props.accessTokenTtl()), sessionExpires);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .audience(List.of(props.audience()))
            .subject(operatorId)
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(accessExpires)
            .id(UUID.randomUUID().toString())
            .claim("typ", TYPE)
            .claim("roles", roles)
            .claim("centers", centers)
            .claim("sid", sessionId)
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKey().getKeyID()).build();
    String access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    String refresh = random();
    store.insertRefresh(hash(refresh), sessionId, now, sessionExpires);
    return new Tokens(access, accessExpires, refresh, sessionExpires, sessionId);
  }

  /** 256비트 무작위 값(base64url). 티켓·refresh·state·nonce·verifier 공용. */
  public static String random() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  public static String hash(String token) {
    return HexFormat.of().formatHex(sha256(token));
  }

  /** PKCE S256: BASE64URL(SHA-256(verifier)). */
  public static String s256(String verifier) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
