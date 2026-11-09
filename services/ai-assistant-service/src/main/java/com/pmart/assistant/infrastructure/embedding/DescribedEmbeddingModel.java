package com.pmart.assistant.infrastructure.embedding;

import org.springframework.ai.embedding.EmbeddingModel;

/** Spring AI {@link EmbeddingModel} + 자기 식별(이름·버전·차원·전처리). 제공자 모듈을 붙일 때도 이 형태로 감싼다. */
public interface DescribedEmbeddingModel extends EmbeddingModel {

  EmbeddingSpec spec();

  @Override
  default int dimensions() {
    return spec().dim();
  }

  /** 질문 임베딩. 모델이 질문에 지시문을 권하면 여기서 붙인다(문서는 {@link #embed(String)} 그대로). */
  default float[] embedQuery(String question) {
    return embed(question);
  }
}
