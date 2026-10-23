package com.pmart.auth.infrastructure.keys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RS256 서명키 묶음. 첫 키로 서명하고, 모든 키의 공개키를 JWKS 로 내보낸다(키 교체 시 이전 키로 서명된 토큰도 만료까지 검증).
 *
 * <p>키 교체 순서: 새 키를 목록 뒤에 추가해 배포(JWKS 전파) → 새 키를 맨 앞으로 옮겨 배포(서명 전환) → 토큰 최대 수명이 지난 뒤 옛 키 제거.
 */
public final class SigningKeyRing {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyRing.class);

  private final RSAKey active;
  private final JWKSet privateSet;

  private SigningKeyRing(List<RSAKey> keys) {
    if (keys.isEmpty()) {
      throw new IllegalStateException("서명키가 없다");
    }
    this.active = keys.getFirst();
    this.privateSet = new JWKSet(new ArrayList<JWK>(keys));
  }

  public static SigningKeyRing from(AuthProperties.Signing signing) {
    if (signing.keys().isEmpty()) {
      if (!signing.ephemeral()) {
        throw new IllegalStateException(
            "pmart.auth.signing.keys 가 비어 있다. 운영 키는 Secret Manager 로 주입한다(임시 키는 local/test 프로파일 전용).");
      }
      log.warn("임시 서명키로 기동한다(local/test 전용). 재시작하면 발급한 토큰이 모두 무효가 된다.");
      return new SigningKeyRing(List.of(generate("ephemeral-" + UUID.randomUUID())));
    }
    return new SigningKeyRing(
        signing.keys().stream().map(k -> parse(k.kid(), k.privateKey())).toList());
  }

  /** 서명용(개인키 포함). 외부로 내보내지 않는다. */
  public JWKSet privateKeys() {
    return privateSet;
  }

  public RSAKey activeKey() {
    return active;
  }

  /** JWKS 응답 본문(공개키만). */
  public Map<String, Object> publicJwks() {
    return privateSet.toPublicJWKSet().toJSONObject(true);
  }

  static RSAKey parse(String kid, String pem) {
    if (kid == null || kid.isBlank() || pem == null || pem.isBlank()) {
      throw new IllegalArgumentException(
          "pmart.auth.signing.keys[*] 에 kid 와 private-key 가 모두 필요하다");
    }
    try {
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      KeyFactory factory = KeyFactory.getInstance("RSA");
      RSAPrivateCrtKey priv =
          (RSAPrivateCrtKey)
              factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
      RSAPublicKey pub =
          (RSAPublicKey)
              factory.generatePublic(
                  new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
      return toJwk(kid, pub, priv);
    } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
      // 키 내용은 예외 메시지에도 싣지 않는다.
      throw new IllegalStateException("서명키 " + kid + " 를 읽을 수 없다(PKCS#8 PEM RSA 개인키 필요)", e);
    }
  }

  static RSAKey generate(String kid) {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair pair = gen.generateKeyPair();
      return toJwk(kid, (RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey toJwk(String kid, RSAPublicKey pub, RSAPrivateCrtKey priv) {
    return new RSAKey.Builder(pub)
        .privateKey(priv)
        .keyID(kid)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }
}
