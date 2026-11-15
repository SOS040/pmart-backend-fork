package com.pmart.notification.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.notification.*}.
 *
 * @param orderBaseUrl order-service(받는 고객·상품명 — {@code GET /internal/orders/{id}}, 내부 호출)
 */
@ConfigurationProperties(prefix = "pmart.notification")
public record NotificationProperties(@Nullable String orderBaseUrl) {}
