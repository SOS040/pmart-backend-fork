package com.pmart.customerassistant.application;

import com.pmart.customerassistant.domain.Authority;
import com.pmart.customerassistant.domain.Lifecycle;
import com.pmart.customerassistant.domain.RankingPolicy;
import com.pmart.customerassistant.domain.RetrievedChunk;
import com.pmart.customerassistant.infrastructure.ChunkStore;
import com.pmart.customerassistant.infrastructure.SnapshotStore.SnapshotRow;
import com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 고정된 스냅숏에서 검색 → 임계값 → 지위·우선순위 정렬 → 대체 제안 문서 동반 근거.
 *
 * <p>스냅숏의 임베딩 모델·차원이 지금 모델과 다르면 검색하지 않는다(다른 모델의 벡터를 섞지 않는다) — 색인 재실행이 필요한 설정 문제다.
 */
@Component
public class Retriever {

  /**
   * @param topSimilarity 임계값 적용 전 1위 후보의 혼합 유사도(후보 없으면 0) — 임계값 맞추기용 기록(숫자만 로그)
   */
  public record Retrieval(
      List<RetrievedChunk> ranked, List<RetrievedChunk> companions, double topSimilarity) {

    /** 상위 근거의 절 제목(문서 메타데이터 — 질문·본문 아님), 로그용. */
    public String topSections() {
      return ranked.stream()
          .limit(3)
          .map(
              c ->
                  c.path().replaceAll(".*/", "")
                      + "#"
                      + String.valueOf(c.section()).replaceAll(".*> ", ""))
          .toList()
          .toString();
    }
  }

  public static class SnapshotModelMismatchException extends RuntimeException {
    public SnapshotModelMismatchException(String message) {
      super(message);
    }
  }

  private final ChunkStore chunks;
  private final DescribedEmbeddingModel embeddings;
  private final AssistantProperties.Retrieval props;

  public Retriever(
      ChunkStore chunks, DescribedEmbeddingModel embeddings, AssistantProperties props) {
    this.chunks = chunks;
    this.embeddings = embeddings;
    this.props = props.getRetrieval();
  }

  public Retrieval retrieve(
      SnapshotRow snapshot, String question, boolean history, Set<String> allowedScopes) {
    var spec = embeddings.spec();
    if (!snapshot.embeddingModel().equals(spec.model())
        || !snapshot.embeddingModelVersion().equals(spec.version())
        || snapshot.embeddingDim() != spec.dim()
        || !snapshot.preprocessingHash().equals(spec.preprocessingHash())) {
      throw new SnapshotModelMismatchException("활성 스냅숏의 임베딩 모델이 현재 설정과 다릅니다 — 재색인 필요");
    }
    float[] q = embeddings.embedQuery(question);
    List<Lifecycle> excluded = Lifecycle.excludedFor(history);
    ChunkStore.Query query =
        new ChunkStore.Query(
            snapshot.snapshotId(),
            q,
            question,
            allowedScopes,
            excluded,
            props.getCosineWeight(),
            props.getKeywordWeight(),
            props.getCandidates());
    List<RetrievedChunk> candidates = chunks.search(query);
    double top = candidates.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0);
    List<RetrievedChunk> ranked = ranking().rank(candidates, props.getTopK());
    Set<String> proposedDocs = new LinkedHashSet<>();
    for (RetrievedChunk c : ranked) {
      if (c.lifecycle() == Lifecycle.SUPERSESSION_PROPOSED) {
        proposedDocs.addAll(c.proposedSupersededBy());
      }
    }
    List<RetrievedChunk> companions = chunks.bestPerDocument(query, proposedDocs);
    return new Retrieval(ranked, companions, top);
  }

  RankingPolicy ranking() {
    return new RankingPolicy(
        props.getThreshold(),
        Map.of(
            Authority.CONFIRMED, props.getConfirmedWeight(),
            Authority.ADOPTED_DESIGN, props.getAdoptedWeight(),
            Authority.PROPOSAL, props.getProposalWeight(),
            Authority.ASSUMPTION, props.getAssumptionWeight(),
            Authority.UNDECIDED, 0.0,
            Authority.EXTERNAL_REFERENCE, 0.0),
        props.getPrecedenceWeight());
  }
}
