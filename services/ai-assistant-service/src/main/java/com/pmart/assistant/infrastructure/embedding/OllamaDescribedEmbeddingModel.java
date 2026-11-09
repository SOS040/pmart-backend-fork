package com.pmart.assistant.infrastructure.embedding;

import com.pmart.assistant.domain.RequestHashing;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * LAN Ollama 임베딩(ADR-0051 결정 6, 모델 Qwen3-Embedding-0.6B — 2026-11-09 비교 평가로 결정). Spring AI {@code
 * OllamaEmbeddingModel} 을 감싸 이름·버전·차원·전처리를 붙인다.
 *
 * <p>질문({@link #embedQuery})에만 모델 권장 지시문을 붙이고 문서는 그대로다. 지시문이 바뀌면 전처리 식별이 바뀌어 재색인이 필요하다고 드러난다. 전처리
 * 식별은 SHA-256(64자) — 저장 컬럼이 char(64) 라 짧은 값은 공백이 붙어 비교가 어긋난다.
 */
public final class OllamaDescribedEmbeddingModel implements DescribedEmbeddingModel {

  static final String PREPROCESSING = "ollama-embed|raw-document|query-instruct:";

  private final EmbeddingModel delegate;
  private final EmbeddingSpec spec;
  private final @Nullable String queryInstruction;

  /**
   * @param model Ollama 모델 이름(예: qwen3-embedding:0.6b)
   * @param version 모델 버전 — Ollama 모델 ID(ollama list 의 ID). 모델 파일이 바뀌면 바꾼다(재색인)
   * @param queryInstruction 질문 앞에 붙일 지시문(없으면 질문도 그대로)
   */
  public OllamaDescribedEmbeddingModel(
      EmbeddingModel delegate,
      String model,
      String version,
      int dim,
      @Nullable String queryInstruction) {
    this.delegate = delegate;
    this.queryInstruction =
        queryInstruction == null || queryInstruction.isBlank() ? null : queryInstruction;
    this.spec =
        new EmbeddingSpec(
            "ollama:" + model,
            version,
            dim,
            RequestHashing.sha256(
                PREPROCESSING + (this.queryInstruction == null ? "" : this.queryInstruction)),
            true);
  }

  @Override
  public EmbeddingSpec spec() {
    return spec;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return delegate.call(request);
  }

  @Override
  public float[] embed(Document document) {
    return delegate.embed(document);
  }

  @Override
  public float[] embed(String text) {
    return delegate.embed(text);
  }

  @Override
  public float[] embedQuery(String question) {
    return delegate.embed(queryInstruction == null ? question : queryInstruction + question);
  }
}
