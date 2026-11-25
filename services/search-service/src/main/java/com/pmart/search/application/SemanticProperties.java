package com.pmart.search.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 의미 검색 설정(ADR-0088, 2026-11-25 — pmart-config {@code search-service-<프로필>.yml}). {@code enabled} 가
 * 아니거나 값이 빠지면 지금 검색 그대로(꼬리표·벡터를 만들지 않고 kNN 도 넣지 않는다 — 기본값으로 대신하지 않음).
 *
 * @param ollamaBaseUrl RAG 묶음 ollama(LAN pc2 http://ollama:11434)
 * @param embeddingModel 벡터 모델(지금 qwen3-embedding:0.6b, 1024차원 — 색인 dims 와 같아야 한다)
 * @param tagModel 꼬리표 모델(지금 qwen3:4b)
 * @param queryInstruction 검색어 임베딩 앞말(qwen3-embedding 은 검색어에 지시문을 붙인다)
 * @param similarity kNN 에 넣을 최소 코사인 비슷함
 * @param knnBoost kNN 점수 가중치(키워드 점수보다 낮게)
 * @param knnK kNN 으로 더할 최대 상품 수
 * @param queryTimeout 검색어 임베딩 상한(넘으면 키워드만)
 * @param tagTimeout 꼬리표 한 상품 상한
 * @param interval 뒤 작업 주기
 * @param batchSize 한 주기에 새로 만들 상품 수
 */
@ConfigurationProperties(prefix = "pmart.search.semantic")
public record SemanticProperties(
    boolean enabled,
    @Nullable String ollamaBaseUrl,
    @Nullable String embeddingModel,
    @Nullable String tagModel,
    @Nullable String queryInstruction,
    @Nullable Double similarity,
    @Nullable Double knnBoost,
    @Nullable Integer knnK,
    @Nullable Duration queryTimeout,
    @Nullable Duration tagTimeout,
    @Nullable Duration interval,
    @Nullable Integer batchSize,
    @Nullable String keepAlive) {

  /** 검색 쪽(kNN)을 켤 수 있나. */
  public boolean searchReady() {
    return enabled
        && ollamaBaseUrl != null
        && embeddingModel != null
        && similarity != null
        && knnBoost != null
        && knnK != null
        && knnK > 0
        && queryTimeout != null;
  }

  /** 뒤 작업(꼬리표·벡터 만들기)을 돌릴 수 있나. */
  public boolean enrichReady() {
    return searchReady()
        && tagModel != null
        && tagTimeout != null
        && batchSize != null
        && batchSize > 0;
  }

  public String instruction() {
    return queryInstruction == null ? "" : queryInstruction;
  }
}
