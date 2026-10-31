package com.pmart.delivery.serviceability.infrastructure.kakao;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.serviceability.kakao.*} — 카카오 로컬 API(주소 검색·좌표→주소, ADR-0023). 키는 Secret
 * Manager·환경변수로만.
 *
 * @param baseUrl API 주소
 * @param restApiKey REST API 키. 비어 있으면 주소 변환과 판정이 CONFIG_REQUIRED
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout 응답 타임아웃(넘기면 DEPENDENCY_UNAVAILABLE — 읽기 전용이라 결과 불명이 없다)
 */
@ConfigurationProperties(prefix = "pmart.serviceability.kakao")
public record KakaoProperties(
    String baseUrl, @Nullable String restApiKey, Duration connectTimeout, Duration readTimeout) {

  public KakaoProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://dapi.kakao.com" : baseUrl;
    restApiKey = restApiKey == null || restApiKey.isBlank() ? null : restApiKey.strip();
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
  }
}
