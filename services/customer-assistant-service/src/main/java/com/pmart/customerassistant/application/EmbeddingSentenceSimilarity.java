package com.pmart.customerassistant.application;

import com.pmart.customerassistant.domain.CitationVerifier;
import com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 인용 검사의 의미 유사도(2026-11-21): 답 문장과 근거 단위를 같은 임베딩 모델로 한 번에 임베딩해 문장마다 최대 코사인. 임베딩이 실패하면 0 을 돌려준다 — 의미
 * 검사 없이 bigram 결과대로(답 전체를 의존성 장애로 만들지 않는다). 문장·근거는 로그에 남기지 않는다.
 */
public class EmbeddingSentenceSimilarity implements CitationVerifier.SentenceSimilarity {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingSentenceSimilarity.class);

  private final DescribedEmbeddingModel embeddings;

  public EmbeddingSentenceSimilarity(DescribedEmbeddingModel embeddings) {
    this.embeddings = embeddings;
  }

  @Override
  public double[] best(List<String> sentences, List<String> evidenceUnits) {
    double[] out = new double[sentences.size()];
    List<String> all = new ArrayList<>(sentences);
    all.addAll(evidenceUnits);
    List<float[]> vectors;
    try {
      vectors = embeddings.embed(all);
    } catch (RuntimeException e) {
      log.warn("citation similarity embedding failed {}", e.getClass().getSimpleName());
      return out;
    }
    if (vectors.size() != all.size()) {
      return out;
    }
    for (int i = 0; i < sentences.size(); i++) {
      double best = 0;
      for (int j = sentences.size(); j < all.size(); j++) {
        best = Math.max(best, cosine(vectors.get(i), vectors.get(j)));
      }
      out[i] = best;
    }
    return out;
  }

  static double cosine(float[] a, float[] b) {
    if (a.length != b.length || a.length == 0) {
      return 0;
    }
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
