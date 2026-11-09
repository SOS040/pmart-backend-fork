package com.pmart.assistant;

import com.pmart.assistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.assistant.infrastructure.embedding.EmbeddingSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 임베딩 비교 평가 전용(ADR-0051 D3, 2026-11-09): 로컬 Ollama {@code /api/embed} 를 부른다. 시스템 속성 {@code
 * pmart.eval.ollama-model} 이 있을 때만 쓰인다 — 평소 시험은 해시 기준선 그대로. 운영 코드가 아니다(모델을 고른 뒤 Spring AI Ollama
 * 모듈로 운영 구현을 따로 만든다).
 *
 * <p>질문만 모델 권장 지시문을 붙인다(Qwen3-Embedding: "Instruct: …\nQuery: …", 문서는 그대로). BGE-M3 는 지시문 없음.
 */
final class OllamaEvalEmbedding implements DescribedEmbeddingModel {

  static final String QWEN_INSTRUCT =
      "Instruct: Given a question about company policy or operating procedures, retrieve the"
          + " document passages that answer it\nQuery: ";

  final String model;
  final AtomicBoolean queryMode = new AtomicBoolean();
  private final RestClient http;
  private final @Nullable String queryPrefix;
  private int dim;
  long calls;
  long nanos;

  OllamaEvalEmbedding(String baseUrl, String model) {
    this.model = model;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofSeconds(5));
    f.setReadTimeout(Duration.ofSeconds(120));
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    this.queryPrefix = model.startsWith("qwen3-embedding") ? QWEN_INSTRUCT : null;
    this.dim = embedRaw("dimension probe").length;
  }

  /** 시스템 속성이 있으면 만든다. */
  static @Nullable OllamaEvalEmbedding fromSystemProperties() {
    String m = System.getProperty("pmart.eval.ollama-model", "");
    if (m.isBlank()) {
      return null;
    }
    return new OllamaEvalEmbedding(
        System.getProperty("pmart.eval.ollama-url", "http://localhost:11434"), m);
  }

  @Override
  public EmbeddingSpec spec() {
    // 전처리 식별은 SHA-256(64자) — 컬럼이 char(64) 라 짧은 문자열은 공백이 붙어 스냅숏 비교가 어긋난다(2026-11-09)
    return new EmbeddingSpec(
        "ollama:" + model,
        "eval",
        dim,
        com.pmart.assistant.domain.RequestHashing.sha256(
            queryPrefix == null ? "raw" : "query-instruct|" + queryPrefix),
        true);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> out = new ArrayList<>();
    int i = 0;
    for (String t : request.getInstructions()) {
      out.add(new Embedding(embed(t), i++));
    }
    return new EmbeddingResponse(out);
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText());
  }

  @Override
  public float[] embed(String text) {
    String input = queryMode.get() && queryPrefix != null ? queryPrefix + text : text;
    return embedRaw(input);
  }

  private float[] embedRaw(String input) {
    long t0 = System.nanoTime();
    JsonNode body =
        http.post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("model", model, "input", List.of(input)))
            .retrieve()
            .body(JsonNode.class);
    nanos += System.nanoTime() - t0;
    calls++;
    JsonNode v = body.path("embeddings").path(0);
    float[] f = new float[v.size()];
    for (int i = 0; i < f.length; i++) {
      f[i] = (float) v.get(i).asDouble();
    }
    return f;
  }
}
