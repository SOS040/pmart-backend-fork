package com.pmart.pricing.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 가격 일괄 등록 배치·행 저장(JDBC). */
@Repository
public class PriceImportStore {

  public record BatchRow(
      String batchId,
      String format,
      String sourceSystem,
      @Nullable String sourceName,
      String state,
      int version,
      @Nullable Instant effectiveFrom,
      @Nullable String approvalTargetRef,
      @Nullable String approvalId,
      Instant checkedAt,
      String createdBy,
      Instant createdAt,
      @Nullable Instant submittedAt) {}

  public record LineRow(
      String batchId,
      String seedProductId,
      int lineNo,
      @Nullable String productName,
      @Nullable String productId,
      @Nullable String sku,
      @Nullable Long bundlePrice,
      @Nullable Long basePrice,
      boolean edited,
      boolean excluded,
      @Nullable Long currentPrice,
      @Nullable String changeType,
      String status) {}

  private final JdbcTemplate jdbc;

  public PriceImportStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertBatch(BatchRow b) {
    jdbc.update(
        "INSERT INTO price_import_batch (batch_id, format, source_system, source_name, state, version,"
            + " effective_from, approval_target_ref, approval_id, checked_at, created_by, created_at,"
            + " submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        b.batchId(),
        b.format(),
        b.sourceSystem(),
        b.sourceName(),
        b.state(),
        b.version(),
        ts(b.effectiveFrom()),
        b.approvalTargetRef(),
        b.approvalId(),
        Timestamp.from(b.checkedAt()),
        b.createdBy(),
        Timestamp.from(b.createdAt()),
        ts(b.submittedAt()));
  }

  public Optional<BatchRow> batch(String batchId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM price_import_batch WHERE batch_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            PriceImportStore::mapBatch,
            batchId)
        .stream()
        .findFirst();
  }

  /** 대조 시각을 바꾸고 버전을 올린다. */
  public void touch(String batchId, @Nullable Instant checkedAt) {
    jdbc.update(
        "UPDATE price_import_batch SET version = version + 1,"
            + " checked_at = COALESCE(?, checked_at) WHERE batch_id = ?",
        ts(checkedAt),
        batchId);
  }

  public void markSubmitted(
      String batchId, @Nullable Instant effectiveFrom, String targetRef, Instant at) {
    jdbc.update(
        "UPDATE price_import_batch SET state = 'SUBMITTED', version = version + 1, effective_from = ?,"
            + " approval_target_ref = ?, submitted_at = ? WHERE batch_id = ?",
        ts(effectiveFrom),
        targetRef,
        Timestamp.from(at),
        batchId);
  }

  /** 승인 결과 연결. 제출된 배치만 바꾼다(같은 승인 재수신·다른 승인은 무시). */
  public boolean markDecided(String batchId, String approvalId, String state) {
    return jdbc.update(
            "UPDATE price_import_batch SET state = ?, approval_id = ?, version = version + 1"
                + " WHERE batch_id = ? AND state = 'SUBMITTED'",
            state,
            approvalId,
            batchId)
        > 0;
  }

  public void insertLine(LineRow l) {
    jdbc.update(
        "INSERT INTO price_import_line (batch_id, seed_product_id, line_no, product_name, product_id, sku,"
            + " bundle_price, base_price, edited, excluded, current_price, change_type, status)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        l.batchId(),
        l.seedProductId(),
        l.lineNo(),
        l.productName(),
        l.productId(),
        l.sku(),
        l.bundlePrice(),
        l.basePrice(),
        l.edited(),
        l.excluded(),
        l.currentPrice(),
        l.changeType(),
        l.status());
  }

  public void updateLine(LineRow l) {
    jdbc.update(
        "UPDATE price_import_line SET product_id = ?, sku = ?, base_price = ?, edited = ?, excluded = ?,"
            + " current_price = ?, change_type = ?, status = ? WHERE batch_id = ? AND seed_product_id = ?",
        l.productId(),
        l.sku(),
        l.basePrice(),
        l.edited(),
        l.excluded(),
        l.currentPrice(),
        l.changeType(),
        l.status(),
        l.batchId(),
        l.seedProductId());
  }

  public List<LineRow> lines(String batchId) {
    return jdbc.query(
        "SELECT * FROM price_import_line WHERE batch_id = ? ORDER BY line_no",
        PriceImportStore::mapLine,
        batchId);
  }

  private static BatchRow mapBatch(ResultSet rs, int i) throws SQLException {
    return new BatchRow(
        rs.getString("batch_id"),
        rs.getString("format"),
        rs.getString("source_system"),
        rs.getString("source_name"),
        rs.getString("state"),
        rs.getInt("version"),
        instant(rs, "effective_from"),
        rs.getString("approval_target_ref"),
        rs.getString("approval_id"),
        rs.getTimestamp("checked_at").toInstant(),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "submitted_at"));
  }

  private static LineRow mapLine(ResultSet rs, int i) throws SQLException {
    return new LineRow(
        rs.getString("batch_id"),
        rs.getString("seed_product_id"),
        rs.getInt("line_no"),
        rs.getString("product_name"),
        rs.getString("product_id"),
        rs.getString("sku"),
        rs.getObject("bundle_price", Long.class),
        rs.getObject("base_price", Long.class),
        rs.getBoolean("edited"),
        rs.getBoolean("excluded"),
        rs.getObject("current_price", Long.class),
        rs.getString("change_type"),
        rs.getString("status"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
