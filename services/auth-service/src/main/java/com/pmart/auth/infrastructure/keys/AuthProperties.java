package com.pmart.auth.infrastructure.keys;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.auth.*} — 서비스 신원 토큰(ADR-0011).
 *
 * @param issuer 발급 토큰의 {@code iss}
 * @param signing 서명키
 * @param serviceToken 서비스 토큰 정책
 * @param serviceClients 등록된 서비스 클라이언트. secret 원문이 아니라 해시만 둔다(Secret Manager/ConfigMap 주입).
 */
@ConfigurationProperties(prefix = "pmart.auth")
public record AuthProperties(
    String issuer, Signing signing, ServiceToken serviceToken, List<ServiceClient> serviceClients) {

  public AuthProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("pmart.auth.issuer 를 설정해야 한다");
    }
    signing = signing == null ? new Signing(List.of(), false) : signing;
    serviceToken = serviceToken == null ? new ServiceToken(null) : serviceToken;
    serviceClients = serviceClients == null ? List.of() : List.copyOf(serviceClients);
    Set<String> ids = new HashSet<>();
    for (ServiceClient c : serviceClients) {
      if (!ids.add(c.clientId())) {
        throw new IllegalArgumentException("pmart.auth.service-clients 중복: " + c.clientId());
      }
    }
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

  /**
   * @param ttl 서비스 토큰 수명. 기본 5분 — 짧게 두고 호출 측이 캐시한다.
   */
  public record ServiceToken(Duration ttl) {
    public ServiceToken {
      ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
      if (ttl.isNegative() || ttl.isZero()) {
        throw new IllegalArgumentException("pmart.auth.service-token.ttl 은 양수여야 한다");
      }
    }
  }

  /**
   * @param clientId 서비스명(spring.application.name)
   * @param secretHash {@code {bcrypt}...} 형식(DelegatingPasswordEncoder)
   */
  public record ServiceClient(String clientId, String secretHash) {}
}
