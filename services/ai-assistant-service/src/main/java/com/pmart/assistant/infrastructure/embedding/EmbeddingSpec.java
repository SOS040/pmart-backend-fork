package com.pmart.assistant.infrastructure.embedding;

/**
 * 임베딩 모델의 식별. 스냅숏·캐시 키에 모두 들어간다 — 이름·버전·차원·전처리 중 하나라도 다르면 벡터를 섞지 않는다.
 *
 * @param semantic 의미 임베딩인가(false 면 기준선 해시 — retrievalMode=BASELINE_HASH)
 */
public record EmbeddingSpec(
    String model, String version, int dim, String preprocessingHash, boolean semantic) {

  /** 응답 model.embedding 표시: 이름@버전/차원. */
  public String describe() {
    return model + "@" + version + "/" + dim;
  }
}
