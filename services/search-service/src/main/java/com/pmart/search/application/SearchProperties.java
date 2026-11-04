package com.pmart.search.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.search.*}.
 *
 * @param elasticsearchUrl Elasticsearch 주소(ADR-0031). 이 서비스는 조회만 한다(색인은 Logstash)
 * @param productBaseUrl product-service 주소 — 공개 카테고리 트리(이름 경로 categoryPath)
 */
@ConfigurationProperties(prefix = "pmart.search")
public record SearchProperties(
    String elasticsearchUrl, @DefaultValue("http://product-service:8101") String productBaseUrl) {}
