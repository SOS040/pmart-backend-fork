package com.pmart.auth.infrastructure.keys;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.auth.*} — 토큰 발급자·서명키(ADR-0011). 서비스 신원 토큰·서비스 클라이언트 등록부는 없앴다(ADR-0026).
 *
 * @param issuer 발급 토큰의 {@code iss}
 * @param signing 서명키
 */
@ConfigurationProperties(prefix = "pmart.auth")
public record AuthProperties(String issuer, Signing signing) {

  public AuthProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("pmart.auth.issuer 를 설정해야 한다");
    }
    signing = signing == null ? new Signing(List.of(), false) : signing;
  }

  /**
   * 서명키.
   *
   * @param keys PKCS#8 PEM RSA 개인키 목록. 첫 번째가 활성(서명) 키, 나머지는 교체 중 검증용으로 JWKS 에만 싣는다.
   * @param ephemeral 키가 없을 때 기동 시 임시 키를 만든다. local/test 전용 — 재시작하면 기존 토큰이 모두 무효.
   */
  public record Signing(List<Key> keys, boolean ephemeral) {
    public Signing {
      keys = keys == null ? List.of() : List.copyOf(keys);
    }
  }

  /** {@code kid} + PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}). */
  public record Key(String kid, String privateKey) {}
}
