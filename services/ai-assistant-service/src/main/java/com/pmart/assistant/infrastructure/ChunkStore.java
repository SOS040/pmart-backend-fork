package com.pmart.assistant.infrastructure;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import com.pmart.assistant.domain.RetrievedChunk;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 청크 검색. 고정된 스냅숏·호출자 허용 접근 범위·생애 제외를 <b>SQL WHERE 로 먼저</b> 건다 — 권한 밖 문서는 점수 계산·모델 입력에 들어가지 않는다. 혼합
 * 유사도 = 코사인(pgvector) 가중 + 키워드(pg_trgm word_similarity) 가중.
 */
@Repository
public class ChunkStore {

  public record Query(
      String snapshotId,
      float[] embedding,
      String text,
      Collection<String> allowedScopes,
      Collection<Lifecycle> excludedLifecycles,
      double cosineWeight,
      double keywordWeight,
      int limit) {}

  private static final String COLUMNS =
      """
      chunk_id, document_id, path, section_path, authority, lifecycle_status, superseded_by,
      proposed_superseded_by, precedence_rank, impl_record_date, impl_note, document_version, effective_date,
      access_scope, content
      """;

  private static final String SIM =
      "(? * (1 - (embedding <=> ?::vector)) + ? * word_similarity(?, content))";

  private final JdbcTemplate jdbc;

  public ChunkStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<RetrievedChunk> search(Query q) {
    if (q.allowedScopes().isEmpty()) {
      return List.of();
    }
    String sql =
        "SELECT "
            + COLUMNS
            + ", "
            + SIM
            + " AS sim FROM doc_chunk"
            + " WHERE snapshot_id = ? AND access_scope = ANY(?) AND NOT (lifecycle_status = ANY(?))"
            + " ORDER BY sim DESC LIMIT ?";
    return jdbc.query(
        con -> {
          var ps = con.prepareStatement(sql);
          int i = bindSim(ps, 1, q);
          ps.setString(i++, q.snapshotId());
          ps.setArray(i++, con.createArrayOf("text", q.allowedScopes().toArray()));
          ps.setArray(
              i++,
              con.createArrayOf("text", q.excludedLifecycles().stream().map(Enum::name).toArray()));
          ps.setInt(i, q.limit());
          return ps;
        },
        ChunkStore::row);
  }

  /** 대체 제안 문서의 동반 근거 — 같은 스냅숏, 지정 문서 ID, <b>같은 접근 범위 필터</b>(3차 검토 1). 문서마다 질문과 가장 가까운 청크 하나. */
  public List<RetrievedChunk> bestPerDocument(Query q, Collection<String> documentIds) {
    if (q.allowedScopes().isEmpty() || documentIds.isEmpty()) {
      return List.of();
    }
    String sql =
        "SELECT DISTINCT ON (document_id) "
            + COLUMNS
            + ", "
            + SIM
            + " AS sim FROM doc_chunk"
            + " WHERE snapshot_id = ? AND access_scope = ANY(?) AND document_id = ANY(?)"
            + " AND NOT (lifecycle_status = ANY(?))"
            + " ORDER BY document_id, sim DESC";
    return jdbc.query(
        con -> {
          var ps = con.prepareStatement(sql);
          int i = bindSim(ps, 1, q);
          ps.setString(i++, q.snapshotId());
          ps.setArray(i++, con.createArrayOf("text", q.allowedScopes().toArray()));
          ps.setArray(i++, con.createArrayOf("text", documentIds.toArray()));
          ps.setArray(
              i,
              con.createArrayOf("text", q.excludedLifecycles().stream().map(Enum::name).toArray()));
          return ps;
        },
        ChunkStore::row);
  }

  public int count(String snapshotId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM doc_chunk WHERE snapshot_id = ?", Integer.class, snapshotId);
    return n == null ? 0 : n;
  }

  private static int bindSim(java.sql.PreparedStatement ps, int start, Query q)
      throws SQLException {
    int i = start;
    ps.setDouble(i++, q.cosineWeight());
    ps.setString(i++, Vectors.literal(q.embedding()));
    ps.setDouble(i++, q.keywordWeight());
    ps.setString(i++, q.text());
    return i;
  }

  private static RetrievedChunk row(ResultSet rs, int n) throws SQLException {
    Array arr = rs.getArray("proposed_superseded_by");
    List<String> proposed =
        arr == null
            ? List.of()
            : Arrays.stream((Object[]) arr.getArray()).map(String::valueOf).toList();
    double sim = rs.getDouble("sim");
    return new RetrievedChunk(
        rs.getString("chunk_id"),
        rs.getString("document_id"),
        rs.getString("path"),
        rs.getString("section_path"),
        Authority.valueOf(rs.getString("authority")),
        Lifecycle.valueOf(rs.getString("lifecycle_status")),
        rs.getString("superseded_by"),
        proposed,
        rs.getInt("precedence_rank"),
        rs.getString("impl_record_date"),
        rs.getString("impl_note"),
        rs.getString("document_version"),
        rs.getString("effective_date"),
        rs.getString("access_scope"),
        rs.getString("content"),
        sim,
        sim);
  }
}
