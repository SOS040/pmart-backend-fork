package com.pmart.notification.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.notification.*}.
 *
 * @param orderBaseUrl order-service(받는 고객·상품명 — {@code GET /internal/orders/{id}}, 내부 호출)
 * @param pushToken 단말 푸시 토큰(0.3.0)
 */
@ConfigurationProperties(prefix = "pmart.notification")
public record NotificationProperties(@Nullable String orderBaseUrl, @Nullable PushToken pushToken) {

  /**
   * {@code pmart.notification.push-token.*}.
   *
   * @param maxDevices 주인(고객·라이더) 한 명이 둘 수 있는 단말 수. 넘으면 가장 오래 갱신되지 않은 단말을 지운다. 없으면 등록 422
   *     CONFIG_REQUIRED(기본값을 지어내지 않는다)
   */
  public record PushToken(@Nullable Integer maxDevices) {}
}
