package com.pmart.notification.application;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.notification.*}.
 *
 * @param orderBaseUrl order-service(받는 고객·상품명 — {@code GET /internal/orders/{id}}, 내부 호출)
 * @param pushToken 단말 푸시 토큰(0.3.0)
 * @param fcm 휴대폰 푸시(2026-11-17 라이더, 같은 날 고객) — 키가 없으면 그 쪽은 꺼짐
 */
@ConfigurationProperties(prefix = "pmart.notification")
public record NotificationProperties(
    @Nullable String orderBaseUrl, @Nullable PushToken pushToken, @Nullable Fcm fcm) {

  /**
   * {@code pmart.notification.fcm.*} — FCM HTTP v1(2026-11-17 사용자 "발송 코드만 먼저, 키는 나중").
   *
   * <p>라이더 앱과 고객 앱은 Firebase 프로젝트가 다르다(2026-11-17 사용자 — 라이더 {@code pmart-123}, 고객 {@code
   * pmart-42b37}). 단말 토큰은 받은 프로젝트로만 보낼 수 있어 키를 받는 사람 종류마다 따로 둔다.
   *
   * @param serviceAccountJson <b>라이더</b> 앱 Firebase 서비스 계정 키 JSON 전체(설정 저장소 {cipher} — 비우면 라이더 푸시
   *     꺼짐). 이름은 처음(라이더만 있을 때) 그대로
   * @param customerServiceAccountJson <b>고객</b> 앱 Firebase 서비스 계정 키 JSON 전체(비우면 고객 푸시 꺼짐)
   * @param customerTemplates 고객에게 푸시로도 보낼 알림 종류(templateKey). 나머지는 알림함만. 비우면 고객 푸시 없음 — 기본값을 지어내지
   *     않는다(설정 저장소, 2026-11-17 사용자 "응 해줘" — 출발·완료·결품·취소·환불 결과)
   * @param tokenUri OAuth 토큰 주소(비우면 키의 token_uri — 시험용)
   * @param sendBaseUrl FCM 주소(비우면 https://fcm.googleapis.com — 시험용)
   */
  public record Fcm(
      @Nullable String serviceAccountJson,
      @Nullable String customerServiceAccountJson,
      @Nullable List<String> customerTemplates,
      @Nullable String tokenUri,
      @Nullable String sendBaseUrl) {}

  /**
   * {@code pmart.notification.push-token.*}.
   *
   * @param maxDevices 주인(고객·라이더) 한 명이 둘 수 있는 단말 수. 넘으면 가장 오래 갱신되지 않은 단말을 지운다. 없으면 등록 422
   *     CONFIG_REQUIRED(기본값을 지어내지 않는다)
   */
  public record PushToken(@Nullable Integer maxDevices) {}
}
