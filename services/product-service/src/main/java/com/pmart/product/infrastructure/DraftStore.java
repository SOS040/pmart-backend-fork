package com.pmart.product.infrastructure;

import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 상품 등록 초안·배치 저장(JDBC — 입력·문제·결과는 jsonb). 상태 변경은 버전 조건부. */
@Repository
public class DraftStore {

  public record DraftRow(
      String draftId,
      @Nullable String batchId,
      @Nullable String sourceSystem,
      @Nullable String sourceKey,
      String state,
      String validationState,
      String problems,
      String input,
      String resolvedCategories,
      @Nullable String draftHash,
      @Nullable String approvalId,
      @Nullable String resultProductId,
      String resultSkus,
      int version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record BatchRow(
      String batchId,
      String format,
      String sourceSystem,
      @Nullable String sourceName,
      String state,
      int productCount,
      int validCount,
      int invalidCount,
      String createdBy,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public DraftStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(DraftRow r) {
    jdbc.update(
        "INSERT INTO product_draft (draft_id, batch_id, source_system, source_key, state, validation_state,"
            + " problems, input, resolved_categories, draft_hash, approval_id, result_product_id, result_skus,"
            + " version, created_by, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?::jsonb,?,?,?,?)",
        r.draftId(),
        r.batchId(),
        r.sourceSystem(),
        r.sourceKey(),
        r.state(),
        r.validationState(),
        r.problems(),
        r.input(),
        r.resolvedCategories(),
        r.draftHash(),
        r.approvalId(),
        r.resultProductId(),
        r.resultSkus(),
        r.version(),
        r.createdBy(),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.updatedAt()));
  }

  /** DRAFT 상태에서 입력·검증 결과 교체(재적재·수정). */
  public void replaceInput(
      String draftId,
      int expectedVersion,
      @Nullable String batchId,
      String validationState,
      String problems,
      String input,
      String resolvedCategories,
      Instant at) {
    int n =
        jdbc.update(
            "UPDATE product_draft SET batch_id = COALESCE(?, batch_id), validation_state = ?, problems = ?::jsonb,"
                + " input = ?::jsonb, resolved_categories = ?::jsonb, version = version + 1, updated_at = ?"
                + " WHERE draft_id = ? AND version = ? AND state = 'DRAFT'",
            batchId,
            validationState,
            problems,
            input,
            resolvedCategories,
            Timestamp.from(at),
            draftId,
            expectedVersion);
    if (n == 0) {
      throw PmartException.versionConflict("초안이 이미 바뀌었거나 제출됐습니다. 다시 조회하세요.");
    }
  }

  /** DRAFT → SUBMITTED, 해시 잠금. */
  public boolean lock(String draftId, String hash, Instant at) {
    return jdbc.update(
            "UPDATE product_draft SET state = 'SUBMITTED', draft_hash = ?, version = version + 1, updated_at = ?"
                + " WHERE draft_id = ? AND state = 'DRAFT' AND validation_state = 'VALID'",
            hash,
            Timestamp.from(at),
            draftId)
        == 1;
  }

  public void markApplied(
      String draftId, String approvalId, String productId, String skusJson, Instant at) {
    jdbc.update(
        "UPDATE product_draft SET state = 'APPLIED', approval_id = ?, result_product_id = ?, result_skus = ?::jsonb,"
            + " version = version + 1, updated_at = ? WHERE draft_id = ? AND state = 'SUBMITTED'",
        approvalId,
        productId,
        skusJson,
        Timestamp.from(at),
        draftId);
  }

  /** 반려·반영 실패. 문제를 덧붙여 남긴다. */
  public void markRejected(String draftId, String approvalId, String problemsJson, Instant at) {
    jdbc.update(
        "UPDATE product_draft SET state = 'REJECTED', approval_id = ?, problems = ?::jsonb, version = version + 1,"
            + " updated_at = ? WHERE draft_id = ? AND state = 'SUBMITTED'",
        approvalId,
        problemsJson,
        Timestamp.from(at),
        draftId);
  }

  public Optional<DraftRow> find(String draftId) {
    return jdbc
        .query("SELECT * FROM product_draft WHERE draft_id = ?", (rs, n) -> draft(rs), draftId)
        .stream()
        .findFirst();
  }

  /** 원천 키의 열린(DRAFT·SUBMITTED) 초안. */
  public Optional<DraftRow> findOpen(String sourceSystem, String sourceKey) {
    return jdbc
        .query(
            "SELECT * FROM product_draft WHERE source_system = ? AND source_key = ? AND state IN ('DRAFT','SUBMITTED')",
            (rs, n) -> draft(rs),
            sourceSystem,
            sourceKey)
        .stream()
        .findFirst();
  }

  /** 이 상품을 갱신 대상(input.targetProductId)으로 지정한 열린(DRAFT·SUBMITTED) 초안 — 수정 초안 중복 방지(2026-11-17). */
  public Optional<DraftRow> findOpenForTarget(String productId) {
    return jdbc
        .query(
            "SELECT * FROM product_draft WHERE input ->> 'targetProductId' = ? AND state IN ('DRAFT','SUBMITTED')"
                + " ORDER BY created_at, draft_id LIMIT 1",
            (rs, n) -> draft(rs),
            productId)
        .stream()
        .findFirst();
  }

  public List<DraftRow> ofBatch(String batchId) {
    return jdbc.query(
        "SELECT * FROM product_draft WHERE batch_id = ? ORDER BY created_at, draft_id",
        (rs, n) -> draft(rs),
        batchId);
  }

  public void insert(BatchRow b) {
    jdbc.update(
        "INSERT INTO product_import_batch (batch_id, format, source_system, source_name, state, product_count,"
            + " valid_count, invalid_count, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        b.batchId(),
        b.format(),
        b.sourceSystem(),
        b.sourceName(),
        b.state(),
        b.productCount(),
        b.validCount(),
        b.invalidCount(),
        b.createdBy(),
        Timestamp.from(b.createdAt()));
  }

  public Optional<BatchRow> findBatch(String batchId) {
    return jdbc
        .query(
            "SELECT * FROM product_import_batch WHERE batch_id = ?",
            (rs, n) ->
                new BatchRow(
                    rs.getString("batch_id"),
                    rs.getString("format"),
                    rs.getString("source_system"),
                    rs.getString("source_name"),
                    rs.getString("state"),
                    rs.getInt("product_count"),
                    rs.getInt("valid_count"),
                    rs.getInt("invalid_count"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant()),
            batchId)
        .stream()
        .findFirst();
  }

  public void updateBatchCounts(String batchId, int products, int valid, int invalid) {
    jdbc.update(
        "UPDATE product_import_batch SET product_count = ?, valid_count = ?, invalid_count = ? WHERE batch_id = ?",
        products,
        valid,
        invalid,
        batchId);
  }

  public void updateBatchState(String batchId, String state) {
    jdbc.update("UPDATE product_import_batch SET state = ? WHERE batch_id = ?", state, batchId);
  }

  private static DraftRow draft(ResultSet rs) throws SQLException {
    return new DraftRow(
        rs.getString("draft_id"),
        rs.getString("batch_id"),
        rs.getString("source_system"),
        rs.getString("source_key"),
        rs.getString("state"),
        rs.getString("validation_state"),
        rs.getString("problems"),
        rs.getString("input"),
        rs.getString("resolved_categories"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        rs.getString("result_product_id"),
        rs.getString("result_skus"),
        rs.getInt("version"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
