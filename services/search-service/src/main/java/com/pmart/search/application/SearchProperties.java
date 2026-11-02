package com.pmart.search.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.search.*}.
 *
 * @param opensearchUrl OpenSearch 주소(PMART_OPENSEARCH_URL)
 * @param contentBaseUrl product-content-service 주소 — 콘텐츠 사건에 없는 상품명·대표 이미지를 공개 카드 API 로 보강
 * @param productBaseUrl product-service 주소 — 공개 카테고리 트리(이름 경로 categoryPath)
 * @param inventoryBaseUrl mfc-inventory-service 주소 — mfcId 를 준 검색의 취급 여부(carried) 사본
 */
@ConfigurationProperties(prefix = "pmart.search")
public record SearchProperties(
    String opensearchUrl,
    String contentBaseUrl,
    @DefaultValue("http://product-service:8101") String productBaseUrl,
    @DefaultValue("http://mfc-inventory-service:8301") String inventoryBaseUrl) {}
