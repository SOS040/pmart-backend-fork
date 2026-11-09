package com.pmart.assistant.infrastructure;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.infrastructure.embedding.OllamaDescribedEmbeddingModel;
import java.net.http.HttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
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
    OllamaEmbeddingModel m =
        OllamaEmbeddingModel.builder()
            .ollamaApi(api)
            .options(
                OllamaEmbeddingOptions.builder()
                    .model(o.getEmbeddingModel())
                    .keepAlive(o.getKeepAlive())
                    .build())
            .modelManagementOptions(noPull())
            .build();
    return new OllamaDescribedEmbeddingModel(
        m, o.getEmbeddingModel(), o.getEmbeddingVersion(), dim, o.getQueryInstruction());
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
    return OllamaChatModel.builder()
        .ollamaApi(api)
        .options(options.build())
        .modelManagementOptions(noPull())
        .build();
  }
}
