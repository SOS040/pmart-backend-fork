package com.pmart.search.application;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.search.*}.
 *
 * @param elasticsearchUrl Elasticsearch 주소(ADR-0031). 상품 색인은 조회만 한다(색인은 Logstash). 최근 검색어·검색 기록 색인은
 *     이 서비스가 쓴다(2026-11-17)
 * @param productBaseUrl product-service 주소 — 공개 카테고리 트리(이름 경로 categoryPath)
 * @param recent 최근 검색어 정책(사용자 결정 2026-11-17). 없으면 기록하지 않고 조회는 CONFIG_REQUIRED
 * @param popular 인기 키워드 정책(사용자 결정 2026-11-17). 없으면 인기 키워드는 빈 목록
 * @param searcherHashKey 검색자 해시(HMAC-SHA256) 비밀키 — 고객 ID 를 짐작해 해시를 되돌리지 못하게(비밀값, LAN 은 {cipher}).
 *     없으면 최근 검색어·인기 키워드 기록을 하지 않는다
 * @param contentBaseUrl product-content-service 주소 — 목록 판매 가능 여부(계약 0.10.0 availability, 카드 일괄 조회).
 *     없으면 availability 를 비운다(꺼짐)
 */
@ConfigurationProperties(prefix = "pmart.search")
public record SearchProperties(
    String elasticsearchUrl,
    @DefaultValue("http://product-service:8101") String productBaseUrl,
    @Nullable Recent recent,
    @Nullable Popular popular,
    @Nullable String searcherHashKey,
    @Nullable Correction correction,
    @Nullable String contentBaseUrl) {

  /**
   * 오타 교정(2026-11-20 ADR-0073). 비면 교정하지 않는다(기본값으로 대신하지 않음).
   *
   * @param fewResults 결과가 이 수 이하이면 "혹시 ○○?" 제안(0건이면 자동 교정)
   */
  public record Correction(@Nullable Integer fewResults) {
    public boolean complete() {
      return fewResults != null && fewResults >= 0;
    }
  }

  /**
   * @param maxItems 고객마다 남기는 최근 검색어 수(지금 10)
   * @param retention 이보다 오래된 최근 검색어는 보이지 않고 매일 지운다(지금 90d)
   */
  public record Recent(@Nullable Integer maxItems, @Nullable Duration retention) {

    /** 정책값이 모두 있고 양수인가. 정책값 미설정을 기본값으로 대신하지 않는다(service-architecture.md). */
    public boolean complete() {
      return maxItems != null
          && maxItems > 0
          && retention != null
          && retention.compareTo(Duration.ZERO) > 0;
    }
  }

  /**
   * @param window 집계 기간(지금 7d)
   * @param size 돌려줄 인기 키워드 수(지금 10)
   * @param minSearchers 이 수 이상의 서로 다른 검색자가 쓴 검색어만(지금 2)
   * @param hiddenKeywords 운영 숨김 목록(부적절한 낱말). 공백 정리·소문자로 비교한다
   * @param refresh 메모리 사본을 다시 계산하는 간격(지금 5m)
   */
  public record Popular(
      @Nullable Duration window,
      @Nullable Integer size,
      @Nullable Integer minSearchers,
      @Nullable List<String> hiddenKeywords,
      @Nullable Duration refresh) {

    public boolean complete() {
      return window != null
          && window.compareTo(Duration.ZERO) > 0
          && size != null
          && size > 0
          && minSearchers != null
          && minSearchers > 0
          && hiddenKeywords != null
          && refresh != null
          && refresh.compareTo(Duration.ZERO) > 0;
    }
  }
}
