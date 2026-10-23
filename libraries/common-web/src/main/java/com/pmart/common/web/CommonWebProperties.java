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

  public boolean isRequireIdempotencyKey() {
    return requireIdempotencyKey;
  }

  public void setRequireIdempotencyKey(boolean requireIdempotencyKey) {
    this.requireIdempotencyKey = requireIdempotencyKey;
  }

  public List<String> getGuardedPathPrefixes() {
    return guardedPathPrefixes;
  }

  public void setGuardedPathPrefixes(List<String> guardedPathPrefixes) {
    this.guardedPathPrefixes = guardedPathPrefixes;
  }
}
