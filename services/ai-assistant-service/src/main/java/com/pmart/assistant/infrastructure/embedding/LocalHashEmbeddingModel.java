package com.pmart.assistant.infrastructure.embedding;

import com.pmart.assistant.domain.RequestHashing;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 기준선 임베딩(외부 호출 없음): 문자 2·3-gram 을 해시 버킷에 부호 있게 더한 뒤 L2 정규화한다. 어휘 겹침을 재는 것이지 의미를 재는 것이 아니다 —
 * retrievalMode=BASELINE_HASH 로 드러낸다.
 *
 * <p>전처리(NFC·소문자·공백 하나로·2~3-gram·FNV-1a 해시)를 바꾸면 {@link #PREPROCESSING} 문자열을 바꾼다 → 전처리 해시가 달라져
 * 캐시·스냅숏이 섞이지 않는다.
 */
public class LocalHashEmbeddingModel implements DescribedEmbeddingModel {

  public static final String NAME = "local-hash-ngram";
  public static final String VERSION = "1";
  static final String PREPROCESSING = "nfc|lower|ws1|char-2-3gram|fnv1a32-signed|l2";

  private final EmbeddingSpec spec;

  public LocalHashEmbeddingModel(int dim) {
    this.spec = new EmbeddingSpec(NAME, VERSION, dim, RequestHashing.sha256(PREPROCESSING), false);
  }

  @Override
  public EmbeddingSpec spec() {
    return spec;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> out = new ArrayList<>();
    List<String> inputs = request.getInstructions();
    for (int i = 0; i < inputs.size(); i++) {
      out.add(new Embedding(vector(inputs.get(i)), i));
    }
    return new EmbeddingResponse(out);
  }

  @Override
  public float[] embed(Document document) {
    return vector(document.getText() == null ? "" : document.getText());
  }

  @Override
  public float[] embed(String text) {
    return vector(text);
  }

  float[] vector(String text) {
    String t =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .strip();
    float[] v = new float[spec.dim()];
    for (int n = 2; n <= 3; n++) {
      for (int i = 0; i + n <= t.length(); i++) {
        String gram = t.substring(i, i + n);
        if (gram.isBlank()) {
          continue;
        }
        int h = fnv1a(gram);
        int bucket = Math.floorMod(h, spec.dim());
        v[bucket] += (h & 0x40000000) == 0 ? 1f : -1f;
      }
    }
    double norm = 0;
    for (float x : v) {
      norm += x * x;
    }
    if (norm > 0) {
      float inv = (float) (1 / Math.sqrt(norm));
      for (int i = 0; i < v.length; i++) {
        v[i] *= inv;
      }
    }
    return v;
  }

  private static int fnv1a(String s) {
    int h = 0x811c9dc5;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= b & 0xff;
      h *= 0x01000193;
    }
    return h;
  }
}
