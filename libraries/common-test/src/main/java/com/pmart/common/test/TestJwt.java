package com.pmart.common.test;

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
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 시험용 JWT. 이 JVM 안에서만 쓰는 RSA 키로 서명하고, 같은 키로 검증하는 {@link #decoder()} 를 준다(운영 키·auth-service 불필요).
 *
 * <p>클레임은 common-security {@code PmartPrincipal} 규약: {@code sub}, {@code
 * typ}(CUSTOMER/RIDER/OPERATOR/SERVICE), {@code roles}, {@code centers}, {@code src}. 통합 시험에서는
 * {@link TestJwtConfiguration} 을 가져와 서비스의 {@code JwtDecoder} 를 대체한다.
 */
public final class TestJwt {

  public static final String ISSUER = "pmart-test";
  private static final RSAKey KEY = generate();

  private TestJwt() {}

  public static JwtDecoder decoder() {
    try {
      return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String customer(String customerId) {
    return token(customerId, "CUSTOMER", List.of(), List.of(), "customer-app");
  }

  public static String rider(String riderId) {
    return token(riderId, "RIDER", List.of(), List.of(), "rider-app");
  }

  /**
   * @param roles 예: MD_LEAD, PRICE_APPROVER
   * @param centers 접근 가능한 센터 ID
   */
  public static String operator(String operatorId, List<String> roles, List<String> centers) {
    return token(operatorId, "OPERATOR", roles, centers, "backoffice-web");
  }

  /** 서비스 신원 토큰(ADR-0011, /internal 호출용). */
  public static String service(String serviceName) {
    return token(serviceName, "SERVICE", List.of(), List.of(), serviceName);
  }

  public static String token(
      String subject, String type, List<String> roles, List<String> centers, String source) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .claim("typ", type)
            .claim("roles", roles)
            .claim("centers", centers)
            .claim("src", source)
            .build();
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("pmart-test").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
