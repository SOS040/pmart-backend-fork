package com.pmart.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.AssistantConfig;
import com.pmart.assistant.infrastructure.embedding.EmbeddingSpec;
import com.pmart.assistant.infrastructure.embedding.LocalHashEmbeddingModel;
import org.junit.jupiter.api.Test;

class LocalHashEmbeddingModelTest {

  private final LocalHashEmbeddingModel model = new LocalHashEmbeddingModel(384);

  @Test
  void deterministicNormalizedAndDeclaredDimension() {
    float[] a = model.embed("주문 취소 절차");
    assertThat(a).hasSize(384).containsExactly(model.embed("주문  취소 절차"));
    double norm = 0;
    for (float x : a) {
      norm += x * x;
    }
    assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(model.dimensions()).isEqualTo(384);
  }

  @Test
  void lexicalOverlapScoresHigher() {
    float[] q = model.embed("봉투값은 얼마");
    assertThat(cos(q, model.embed("봉투값은 주문당 100원 1회다")))
        .isGreaterThan(cos(q, model.embed("라이더 배차 시간")));
  }

  @Test
  void specSeparatesCacheKeys() {
    EmbeddingSpec s384 = model.spec();
    EmbeddingSpec s256 = new LocalHashEmbeddingModel(256).spec();
    assertThat(s384.semantic()).isFalse();
    assertThat(s384.describe()).isEqualTo("local-hash-ngram@1/384");
    assertThat(s384).isNotEqualTo(s256);
    assertThat(s384.preprocessingHash()).hasSize(64);
  }

  private static double cos(float[] a, float[] b) {
    double d = 0;
    for (int i = 0; i < a.length; i++) {
      d += a[i] * b[i];
    }
    return d;
  }

  /** 하드 타임아웃 < lease 기동 검사. */
  @Test
  void timeoutMustBeShorterThanLease() {
    var props = new com.pmart.assistant.application.AssistantProperties();
    props.getProcessing().setTimeout(java.time.Duration.ofSeconds(120));
    props.getProcessing().setLease(java.time.Duration.ofSeconds(120));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> AssistantConfigAccess.validate(props))
        .hasMessageContaining("lease");
  }

  /** 패키지 경계 너머 정적 검사 호출용. */
  static final class AssistantConfigAccess {
    static void validate(com.pmart.assistant.application.AssistantProperties p) {
      try {
        var m =
            AssistantConfig.class.getDeclaredMethod(
                "validate", com.pmart.assistant.application.AssistantProperties.class);
        m.setAccessible(true);
        m.invoke(null, p);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw (RuntimeException) e.getCause();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
