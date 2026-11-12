package com.pmart.auth.application;

import com.pmart.auth.infrastructure.SessionStore;
import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
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
 * 세션 토큰 발급(운영자·고객 공통, ADR-0018). access token 은 RS256(ADR-0011 서명키), refresh token 은 무작위 값이고 DB 에는
 * SHA-256 만 둔다. 두 토큰 모두 세션 절대 만료를 넘지 않는다. 호출자 트랜잭션 안에서 쓴다.
 */
@Component
public class SessionTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  public record Tokens(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      String sessionId) {}

  /** 토큰 내용: 주체·유형·역할·센터. */
  public record Principal(String subject, String type, List<String> roles, List<String> centers) {}

  private final SessionStore store;
  private final JwtEncoder jwtEncoder;
  private final SigningKeyRing keys;
  private final String issuer;
  private final String audience;

  public SessionTokens(
      SessionStore store, JwtEncoder jwtEncoder, SigningKeyRing keys, AuthProperties props) {
    this.store = store;
    this.jwtEncoder = jwtEncoder;
    this.keys = keys;
    this.issuer = props.issuer();
    this.audience = props.audience();
  }

  public Tokens issue(
      Principal p,
      String sessionId,
      Instant sessionExpires,
      Duration accessTtl,
      Duration refreshTtl,
      Instant now) {
    Instant accessExpires = min(now.plus(accessTtl), sessionExpires);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(p.subject())
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(accessExpires)
            .id(UUID.randomUUID().toString())
            .claim("typ", p.type())
            .claim("roles", p.roles())
            .claim("centers", p.centers())
            .claim("sid", sessionId)
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKey().getKeyID()).build();
    String access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant refreshExpires = min(now.plus(refreshTtl), sessionExpires);
    store.insertRefresh(hash(refresh), sessionId, now, refreshExpires);
    return new Tokens(access, accessExpires, refresh, refreshExpires, sessionId);
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
