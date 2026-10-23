package com.pmart.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.gateway.*}.
 *
 * @param upstream 서비스 이름 → 업스트림 기본 URL (라우트 uri 플레이스홀더가 참조)
 * @param publicPaths 인증 없이 통과시키는 작업. 계약(contracts/openapi)에서 {@code security: []} 인 것만.
 * @param rateLimit 요청 처리율 제한(Redis 토큰 버킷). 없으면 비활성.
 */
@ConfigurationProperties(prefix = "pmart.gateway")
public record GatewayProperties(
    Map<String, String> upstream, List<PublicPath> publicPaths, RateLimit rateLimit) {

  public GatewayProperties {
    upstream = upstream == null ? Map.of() : Map.copyOf(upstream);
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    rateLimit = rateLimit == null ? RateLimit.DISABLED : rateLimit;
  }

  /** HTTP 메서드 + Ant 경로 패턴. */
  public record PublicPath(String method, String path) {}

  /**
   * 처리율 제한.
   *
   * @param enabled 끄면 필터가 그대로 통과시킨다
   * @param trustedProxies 클라이언트 IP 판정 시 신뢰하는 앞단 프록시 수({@code X-Forwarded-For} 오른쪽부터). 0 이면 TCP 원격
   *     주소.
   * @param anonymous 토큰 없는 요청(공개 작업) — 클라이언트 IP 단위
   * @param authenticated 유효한 JWT 요청 — 토큰 subject 단위
   * @param exemptPaths 제한하지 않는 경로 패턴(외부 시스템 웹훅 등 IP 가 몰리는 호출)
   */
  public record RateLimit(
      boolean enabled,
      int trustedProxies,
      Bucket anonymous,
      Bucket authenticated,
      List<String> exemptPaths) {

    static final RateLimit DISABLED = new RateLimit(false, 0, null, null, null);

    public RateLimit {
      exemptPaths = exemptPaths == null ? List.of() : List.copyOf(exemptPaths);
      if (enabled && (anonymous == null || authenticated == null)) {
        throw new IllegalArgumentException(
            "pmart.gateway.rate-limit.anonymous/authenticated 를 설정해야 한다(미설정을 무제한으로 대체하지 않는다)");
      }
    }
  }

  /**
   * 토큰 버킷.
   *
   * @param replenishRate 초당 보충 토큰 수(지속 허용 처리율)
   * @param burstCapacity 버킷 크기(순간 허용량)
   */
  public record Bucket(int replenishRate, int burstCapacity) {

    public Bucket {
      if (replenishRate < 1 || burstCapacity < replenishRate) {
        throw new IllegalArgumentException(
            "replenishRate >= 1, burstCapacity >= replenishRate 이어야 한다");
      }
    }
  }
}
