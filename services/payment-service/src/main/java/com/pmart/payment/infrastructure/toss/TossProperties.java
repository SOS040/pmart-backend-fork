package com.pmart.payment.infrastructure.toss;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.payment.toss.*} — 토스페이먼츠 결제위젯 연동(결제 명세 §7.1·§7.2). 키는 Secret Manager·환경변수로만.
 *
 * @param baseUrl API 주소
 * @param secretKey 결제위젯 시크릿 키(gsk). 비어 있으면 결제 시작은 CONFIG_REQUIRED
 * @param clientKey 결제위젯 클라이언트 키(gck, 공개) — 결제 시작 응답 checkout 에 싣는다
 * @param allowLiveKeys 운영 키(live_) 허용. 운영 환경에서만 true — false 인데 live_ 키면 기동을 거부한다
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout 응답 타임아웃(넘기면 결과 불명)
 * @param webhookSecret 웹훅 서명 검증 키. 비어 있으면 모든 웹훅을 401 로 거부한다(조회 작업이 대사를 맡는다)
 */
@ConfigurationProperties(prefix = "pmart.payment.toss")
public record TossProperties(
    String baseUrl,
    @Nullable String secretKey,
    @Nullable String clientKey,
    boolean allowLiveKeys,
    Duration connectTimeout,
    Duration readTimeout,
    @Nullable String webhookSecret) {

  public TossProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.tosspayments.com" : baseUrl;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
    secretKey = blankToNull(secretKey);
    clientKey = blankToNull(clientKey);
    webhookSecret = blankToNull(webhookSecret);
    // 명세 §7.1: 테스트·로컬에서 운영 키면 기동 거부. 키 값은 메시지에 넣지 않는다
    if (!allowLiveKeys
        && ((secretKey != null && secretKey.startsWith("live_"))
            || (clientKey != null && clientKey.startsWith("live_")))) {
      throw new IllegalStateException(
          "pmart.payment.toss: 운영 키(live_)는 allow-live-keys=true 인 운영 환경에서만 쓴다");
    }
    if (secretKey != null
        && !secretKey.startsWith("test_gsk_")
        && !secretKey.startsWith("live_gsk_")) {
      throw new IllegalStateException("pmart.payment.toss.secret-key: 결제위젯 시크릿 키(gsk)가 아니다");
    }
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
