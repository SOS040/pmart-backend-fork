package com.pmart.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pmart.web.*} 설정. */
@ConfigurationProperties(prefix = "pmart.web")
public class CommonWebProperties {

  /** 변경 요청(POST/PUT/PATCH/DELETE)에 Idempotency-Key·X-Source-System 누락 시 400으로 거절. */
  private boolean requireIdempotencyKey = true;

  /** 헤더 필수 검사를 적용할 경로 접두사. */
  private List<String> guardedPathPrefixes = List.of("/api/", "/internal/");

  /**
   * 검사에서 뺄 경로(Ant 패턴). 계약에 Idempotency-Key 가 없는 표준 프로토콜 엔드포인트만(예: OAuth 2.0 토큰 발급, RFC 6749). 업무 변경
   * API 를 넣지 않는다.
   */
  private List<String> excludedPaths = List.of();

  public boolean isRequireIdempotencyKey() {
    return requireIdempotencyKey;
  }

  public void setRequireIdempotencyKey(boolean requireIdempotencyKey) {
    this.requireIdempotencyKey = requireIdempotencyKey;
  }

  public List<String> getExcludedPaths() {
    return excludedPaths;
  }

  public void setExcludedPaths(List<String> excludedPaths) {
    this.excludedPaths = excludedPaths;
  }

  public List<String> getGuardedPathPrefixes() {
    return guardedPathPrefixes;
  }

  public void setGuardedPathPrefixes(List<String> guardedPathPrefixes) {
    this.guardedPathPrefixes = guardedPathPrefixes;
  }
}
