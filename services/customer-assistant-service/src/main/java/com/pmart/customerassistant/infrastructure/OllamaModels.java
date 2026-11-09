package com.pmart.customerassistant.infrastructure;

import com.pmart.customerassistant.application.AssistantProperties;
import com.pmart.customerassistant.infrastructure.embedding.OllamaDescribedEmbeddingModel;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LAN Ollama 연결(ADR-0051 결정 6). 자동 설정 starter 없이 여기서 만든다 — 시간 제한을 서비스 처리 시간 안으로 두고, 모델은 받아 오지
 * 않는다(운영자가 {@code ollama pull} 로 준비 — 없으면 호출이 실패해 503).
 */
public final class OllamaModels {

  private OllamaModels() {}

  public static OllamaApi api(AssistantProperties.Ollama o, RestClient.Builder base) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(o.getConnectTimeout()).build();
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(client);
    f.setReadTimeout(o.getRequestTimeout());
    return OllamaApi.builder()
        .baseUrl(o.getBaseUrl())
        .restClientBuilder(base.clone().requestFactory(f))
        .build();
  }

  private static ModelManagementOptions noPull() {
    return ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build();
  }

  public static OllamaDescribedEmbeddingModel embedding(
      OllamaApi api, AssistantProperties.Ollama o, int dim) {
    return new OllamaDescribedEmbeddingModel(
        new ApiEmbedding(api, o),
        o.getEmbeddingModel(),
        o.getEmbeddingVersion(),
        dim,
        o.getQueryInstruction());
  }

  /**
   * {@code /api/embed} 를 직접 부른다. Spring AI {@code OllamaEmbeddingModel}(2.0.2)은 요청에
   * options·keep_alive 를 싣지 않아(시험에서 확인 — "options":{}) CPU 지정(num_gpu 0)·문맥 길이가 Ollama 에 가지 않는다.
   */
  static final class ApiEmbedding implements EmbeddingModel {
    private final OllamaApi api;
    private final String model;
    private final String keepAlive;
    private final Map<String, Object> options;

    ApiEmbedding(OllamaApi api, AssistantProperties.Ollama o) {
      this.api = api;
      this.model = o.getEmbeddingModel();
      this.keepAlive = o.getKeepAlive();
      Map<String, Object> opts = new LinkedHashMap<>();
      if (o.getEmbeddingNumGpu() != null) {
        opts.put("num_gpu", o.getEmbeddingNumGpu());
      }
      if (o.getEmbeddingNumCtx() != null) {
        opts.put("num_ctx", o.getEmbeddingNumCtx());
      }
      this.options = Map.copyOf(opts);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<float[]> vectors = embedAll(request.getInstructions());
      List<Embedding> out = new ArrayList<>();
      for (int i = 0; i < vectors.size(); i++) {
        out.add(new Embedding(vectors.get(i), i));
      }
      return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
      return embed(document.getText() == null ? "" : document.getText());
    }

    @Override
    public float[] embed(String text) {
      return embedAll(List.of(text)).get(0);
    }

    private List<float[]> embedAll(List<String> inputs) {
      var res =
          api.embed(new OllamaApi.EmbeddingsRequest(model, inputs, keepAlive, options, true, null));
      if (res == null || res.embeddings() == null || res.embeddings().size() != inputs.size()) {
        throw new IllegalStateException("Ollama 임베딩 응답 개수가 요청과 다름");
      }
      return res.embeddings();
    }
  }

  public static ChatModel chat(OllamaApi api, AssistantProperties.Ollama o) {
    var options =
        OllamaChatOptions.builder()
            .model(o.getChatModel())
            .keepAlive(o.getKeepAlive())
            .temperature(o.getChatTemperature())
            .numCtx(o.getChatNumCtx())
            .format("json");
    if (o.isDisableThinking()) {
      options.disableThinking();
    }
    // 재시도 없음: Spring AI 기본 재시도는 같은 입력에서 되풀이되는 Ollama 500 을 네 번 더 불러 60초 처리 시간을 다 썼다(2026-11-09 답변
    // 평가 N08). 실패는 바로 503(DEPENDENCY_UNAVAILABLE) — 같은 Idempotency-Key 로 다시 물을 수 있다
    return OllamaChatModel.builder()
        .ollamaApi(api)
        .options(options.build())
        .modelManagementOptions(noPull())
        .retryTemplate(
            new org.springframework.core.retry.RetryTemplate(
                org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
        .build();
  }
}
