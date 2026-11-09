package com.pmart.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.infrastructure.embedding.OllamaDescribedEmbeddingModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** provider=ollama(ADR-0051): 질문에만 지시문, 지시문·버전이 식별에 들어감, 필수값·시간 제한 기동 검사. */
class OllamaModelsTest {

  static class Recording implements EmbeddingModel {
    final List<String> inputs = new ArrayList<>();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      inputs.addAll(request.getInstructions());
      return new EmbeddingResponse(List.of(new Embedding(new float[] {1, 0}, 0)));
    }

    @Override
    public float[] embed(Document document) {
      return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
      inputs.add(text);
      return new float[] {1, 0};
    }
  }

  @Test
  void onlyQuestionsGetTheInstruction() {
    var rec = new Recording();
    var m = new OllamaDescribedEmbeddingModel(rec, "qwen3-embedding:0.6b", "ac6d", 2, "Q: ");
    m.embed("문서 본문");
    m.embedQuery("질문");
    assertThat(rec.inputs).containsExactly("문서 본문", "Q: 질문");
    assertThat(m.spec().semantic()).isTrue();
    assertThat(m.spec().describe()).isEqualTo("ollama:qwen3-embedding:0.6b@ac6d/2");
    assertThat(m.spec().preprocessingHash()).hasSize(64);
  }

  @Test
  void instructionChangeChangesPreprocessingIdentity() {
    var a = new OllamaDescribedEmbeddingModel(new Recording(), "m", "v", 2, "Q: ");
    var b = new OllamaDescribedEmbeddingModel(new Recording(), "m", "v", 2, "Query: ");
    var none = new OllamaDescribedEmbeddingModel(new Recording(), "m", "v", 2, null);
    assertThat(a.spec().preprocessingHash())
        .isNotEqualTo(b.spec().preprocessingHash())
        .isNotEqualTo(none.spec().preprocessingHash());
  }

  @Test
  void missingSettingsOrLongTimeoutStopStartup() throws Throwable {
    var props = new AssistantProperties();
    props.getModel().setProvider("ollama");
    var validate =
        com.pmart.assistant.application.AssistantConfig.class.getDeclaredMethod(
            "validateOllama", AssistantProperties.class);
    validate.setAccessible(true);
    assertThatThrownBy(() -> invoke(validate, props)).hasMessageContaining("base-url");

    var o = props.getModel().getOllama();
    o.setBaseUrl("http://ollama:11434");
    o.setEmbeddingModel("qwen3-embedding:0.6b");
    o.setEmbeddingVersion("ac6da0dfba84");
    o.setChatModel("qwen3:4b");
    invoke(validate, props); // 통과
    o.setRequestTimeout(Duration.ofSeconds(90)); // 처리 하드 타임아웃(기본 60초)보다 길면 안 됨
    assertThatThrownBy(() -> invoke(validate, props)).hasMessageContaining("request-timeout");
  }

  private static void invoke(java.lang.reflect.Method m, AssistantProperties p) throws Throwable {
    try {
      m.invoke(null, p);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
