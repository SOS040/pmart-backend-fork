package com.pmart.cases.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.cases.*}. 값의 정본은 설정 저장소 {@code case-service*.yml}(ADR-0029).
 *
 * @param orderServiceBaseUrl 고객센터 문의의 본인 주문 확인(order `GET /orders/{id}`). 없으면 주문을 고른 문의만 422
 *     CONFIG_REQUIRED
 */
@ConfigurationProperties("pmart.cases")
public record CaseProperties(@Nullable String orderServiceBaseUrl) {}
