package com.pmart.search.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.search.*}.
 *
 * @param opensearchUrl OpenSearch 주소(PMART_OPENSEARCH_URL)
 * @param contentBaseUrl product-content-service 주소 — 콘텐츠 사건에 없는 상품명·대표 이미지를 공개 카드 API 로 보강
 */
@ConfigurationProperties(prefix = "pmart.search")
public record SearchProperties(String opensearchUrl, String contentBaseUrl) {}
