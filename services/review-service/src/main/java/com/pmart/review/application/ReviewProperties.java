package com.pmart.review.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.review.*}.
 *
 * @param orderBaseUrl order-service(자격 사본·반품 수량 — {@code GET /internal/orders/{id}}, 내부 호출)
 * @param customerBaseUrl customer-service(작성자 표시 — {@code GET
 *     /internal/accounts/{id}/review-author})
 * @param centerMasterBaseUrl center-master-service(자격 목록의 센터 이름 — {@code GET
 *     /internal/centers/{id}}, 2026-11-20 BR-67). 없으면 센터 이름은 null
 * @param contentBaseUrl product-content-service(자격 목록의 상품 사진·옵션 라벨 — {@code GET
 *     /product-contents?productIds=}, 2026-11-20 BR-67). 없으면 사진은 null
 */
@ConfigurationProperties(prefix = "pmart.review")
public record ReviewProperties(
    @Nullable String orderBaseUrl,
    @Nullable String customerBaseUrl,
    @Nullable String centerMasterBaseUrl,
    @Nullable String contentBaseUrl) {}
