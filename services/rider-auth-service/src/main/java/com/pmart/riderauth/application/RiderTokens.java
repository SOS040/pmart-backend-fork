package com.pmart.riderauth.application;

import com.pmart.riderauth.infrastructure.RiderAuthStore;
import com.pmart.riderauth.infrastructure.keys.SigningKeyRing;
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
 * 라이더 토큰 발급. access token 은 RS256({@code iss} = 이 서비스, {@code sub} = riderId, {@code typ} = RIDER —
 * 다른 서비스의 토큰 형태(typ·roles·centers·sid)와 같다), refresh token 은 무작위 값이고 DB 에는 SHA-256 만 둔다. 두 토큰 모두 세션
 * 만료를 넘지 않는다. 호출자 트랜잭션 안에서 쓴다.
 */
@Component
public class RiderTokens {

  public static final String TYPE = "RIDER";

  private static final SecureRandom RANDOM = new SecureRandom();

  public record Tokens(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      String sessionId) {}

  private final RiderAuthStore store;
  private final JwtEncoder jwtEncoder;
  private final SigningKeyRing keys;
  private final RiderAuthProperties props;

  public RiderTokens(
      RiderAuthStore store, JwtEncoder jwtEncoder, SigningKeyRing keys, RiderAuthProperties props) {
    this.store = store;
    this.jwtEncoder = jwtEncoder;
    this.keys = keys;
    this.props = props;
  }

  public Tokens issue(String riderId, String sessionId, Instant sessionExpires, Instant now) {
    Instant accessExpires = min(now.plus(props.accessTokenTtl()), sessionExpires);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .audience(List.of(props.audience()))
            .subject(riderId)
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(accessExpires)
            .id(UUID.randomUUID().toString())
            .claim("typ", TYPE)
            .claim("roles", List.of())
            .claim("centers", List.of())
            .claim("sid", sessionId)
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKey().getKeyID()).build();
    String access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant refreshExpires = min(now.plus(props.refreshTokenTtl()), sessionExpires);
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
