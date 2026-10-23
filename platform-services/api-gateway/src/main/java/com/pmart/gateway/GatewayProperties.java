package com.pmart.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.gateway.*}.
 *
 * @param upstream 서비스 이름 → 업스트림 기본 URL (라우트 uri 플레이스홀더가 참조)
 * @param publicPaths 인증 없이 통과시키는 작업. 계약(contracts/openapi)에서 {@code security: []} 인 것만.
 */
@ConfigurationProperties(prefix = "pmart.gateway")
public record GatewayProperties(Map<String, String> upstream, List<PublicPath> publicPaths) {

  public GatewayProperties {
    upstream = upstream == null ? Map.of() : Map.copyOf(upstream);
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
  }

  /** HTTP 메서드 + Ant 경로 패턴. */
  public record PublicPath(String method, String path) {}
}
