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
 * @param cors 브라우저 CORS. 허용 출처가 비면 CORS 헤더를 내지 않는다.
 */
@ConfigurationProperties(prefix = "pmart.gateway")
public record GatewayProperties(
    Map<String, String> upstream, List<PublicPath> publicPaths, RateLimit rateLimit, Cors cors) {

  public GatewayProperties {
    upstream = upstream == null ? Map.of() : Map.copyOf(upstream);
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    rateLimit = rateLimit == null ? RateLimit.DISABLED : rateLimit;
    cors = cors == null ? new Cors(null) : cors;
  }

  /**
   * 웹(브라우저) 클라이언트 CORS(D8). 쿠키를 쓰지 않고 Bearer 토큰만 쓰므로 자격 증명 허용은 하지 않는다.
   *
   * @param allowedOrigins 허용 출처(정확히 일치. 와일드카드 없음)
   */
  public record Cors(List<String> allowedOrigins) {
    public Cors {
      allowedOrigins =
          allowedOrigins == null
              ? List.of()
              : allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
      if (allowedOrigins.contains("*")) {
        throw new IllegalArgumentException(
            "pmart.gateway.cors.allowed-origins 에 * 를 쓰지 않는다(출처를 나열한다)");
      }
    }
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
   * @param requestedTokens 요청 1건이 쓰는 토큰 수(기본 1). 초당 1건 미만 제한에 쓴다: rate 1·burst 60·requested 60 = 분당
   *     1건.
   */
  public record Bucket(int replenishRate, int burstCapacity, Integer requestedTokens) {

    public Bucket {
      requestedTokens = requestedTokens == null ? 1 : requestedTokens;
      if (replenishRate < 1
          || burstCapacity < replenishRate
          || requestedTokens < 1
          || requestedTokens > burstCapacity) {
        throw new IllegalArgumentException(
            "replenishRate >= 1, burstCapacity >= replenishRate, 1 <= requestedTokens <= burstCapacity 이어야 한다");
      }
    }
  }
}
