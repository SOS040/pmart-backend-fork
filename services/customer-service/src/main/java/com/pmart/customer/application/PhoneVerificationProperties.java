package com.pmart.customer.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.customer.phone-verification.*}(2026-11-19, OCTOMO). 하나라도 없으면 시작·확인은 CONFIG_REQUIRED
 * — 기본값으로 대신하지 않는다. api-key 는 비밀(pmart-config {cipher}, 로그 금지). 바꾸면 재시작(실행 중 갱신 안 함).
 *
 * @param octomoBaseUrl OCTOMO API 주소(https://api.octoverse.kr)
 * @param apiKey OCTOMO 애플리케이션 API Key(마이페이지)
 * @param sendTo 고객이 문자를 보낼 대표번호(표시용, 예 1666-3538)
 * @param ttl 시작 뒤 확인할 수 있는 시간
 * @param withinMinutes OCTOMO 조회 범위(분, 1~60)
 * @param maxAttempts "아직 안 왔음" 허용 횟수(조회마다 월 한도가 준다)
 * @param maxStartsPerDay 계정당 하루 시작 횟수
 */
@ConfigurationProperties(prefix = "pmart.customer.phone-verification")
public record PhoneVerificationProperties(
    @Nullable String octomoBaseUrl,
    @Nullable String apiKey,
    @Nullable String sendTo,
    @Nullable Duration ttl,
    @Nullable Integer withinMinutes,
    @Nullable Integer maxAttempts,
    @Nullable Integer maxStartsPerDay) {

  public boolean complete() {
    return octomoBaseUrl != null
        && !octomoBaseUrl.isBlank()
        && apiKey != null
        && !apiKey.isBlank()
        && sendTo != null
        && !sendTo.isBlank()
        && ttl != null
        && ttl.isPositive()
        && withinMinutes != null
        && withinMinutes >= 1
        && withinMinutes <= 60
        && maxAttempts != null
        && maxAttempts > 0
        && maxStartsPerDay != null
        && maxStartsPerDay > 0;
  }
}
