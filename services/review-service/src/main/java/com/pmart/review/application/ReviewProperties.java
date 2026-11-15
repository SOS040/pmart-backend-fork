package com.pmart.review.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.review.*}.
 *
 * @param orderBaseUrl order-service(자격 사본·반품 수량 — {@code GET /internal/orders/{id}}, 내부 호출)
 * @param customerBaseUrl customer-service(작성자 표시 — {@code GET
 *     /internal/accounts/{id}/review-author})
 */
@ConfigurationProperties(prefix = "pmart.review")
public record ReviewProperties(@Nullable String orderBaseUrl, @Nullable String customerBaseUrl) {}
