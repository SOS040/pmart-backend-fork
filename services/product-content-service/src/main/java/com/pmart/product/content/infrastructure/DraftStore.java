package com.pmart.product.content.infrastructure;

import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 콘텐츠 초안·이미지 메타·배치 저장(JDBC). 상태 변경은 상태·버전 조건부. */
@Repository
public class DraftStore {

  public record DraftRow(
      String draftId,
      @Nullable String sourceSystem,
      @Nullable String sourceKey,
      @Nullable String productId,
      String state,
      @Nullable String name,
      @Nullable String description,
      @Nullable String storageGuide,
      @Nullable String returnGuide,
      String notices,
      String options,
      String contentHash,
      @Nullable String draftHash,
      @Nullable String approvalId,
      @Nullable Integer contentVersion,
      String problems,
      int version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record ImageRow(
      String imageId,
      String draftId,
      String role,
      int sortOrder,
      String bucket,
      String objectKey,
      String contentType,
      @Nullable Integer width,
      @Nullable Integer height,
      long bytes,
      String sha256,
      Instant createdAt) {}

  public record BatchRow(
      String batchId,
      String format,
      String sourceSystem,
      @Nullable String sourceName,
      String bundleSha256,
      String state,
      int productCount,
      int createdCount,
      int updatedCount,
      int unchangedCount,
      int imageCount,
      int uploadedImageCount,
      String createdBy,
      Instant createdAt) {}

  public record ItemRow(String batchId, String sourceKey, String draftId, String outcome) {}

  private final JdbcTemplate jdbc;

  public DraftStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 초안 ----------

  public void insert(DraftRow r) {
    jdbc.update(
        "INSERT INTO content_draft (draft_id, source_system, source_key, product_id, state, name, description,"
            + " storage_guide, return_guide, notices, options, content_hash, draft_hash, approval_id, content_version,"
            + " problems, version, created_by, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?,?,?,?)",
        r.draftId(),
        r.sourceSystem(),
        r.sourceKey(),
        r.productId(),
        r.state(),
        r.name(),
        r.description(),
        r.storageGuide(),
        r.returnGuide(),
        r.notices(),
        r.options(),
        r.contentHash(),
        r.draftHash(),
        r.approvalId(),
        r.contentVersion(),
        r.problems(),
        r.version(),
        r.createdBy(),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.updatedAt()));
  }

  /** DRAFT 상태에서 입력 교체(재적재·수정). */
  public void replaceInput(
      String draftId,
      int expectedVersion,
      @Nullable String productId,
      @Nullable String name,
      @Nullable String description,
      @Nullable String storageGuide,
      @Nullable String returnGuide,
      String notices,
      String options,
      Instant at) {
    int n =
        jdbc.update(
            "UPDATE content_draft SET product_id = ?, name = ?, description = ?, storage_guide = ?, return_guide = ?,"
                + " notices = ?::jsonb, options = ?::jsonb, version = version + 1, updated_at = ?"
                + " WHERE draft_id = ? AND version = ? AND state = 'DRAFT'",
            productId,
            name,
            description,
            storageGuide,
            returnGuide,
            notices,
            options,
            Timestamp.from(at),
            draftId,
            expectedVersion);
    if (n == 0) {
      throw PmartException.versionConflict("초안이 이미 바뀌었거나 제출됐습니다. 다시 조회하세요.");
    }
  }

  public void updateContentHash(String draftId, String hash, Instant at) {
    jdbc.update(
        "UPDATE content_draft SET content_hash = ?, version = version + 1, updated_at = ? WHERE draft_id = ?",
        hash,
        Timestamp.from(at),
        draftId);
  }

  /** DRAFT → SUBMITTED, 해시 잠금. */
  public boolean lock(String draftId, int expectedVersion, String draftHash, Instant at) {
    return jdbc.update(
            "UPDATE content_draft SET state = 'SUBMITTED', draft_hash = ?, version = version + 1, updated_at = ?"
                + " WHERE draft_id = ? AND state = 'DRAFT' AND version = ? AND product_id IS NOT NULL",
            draftHash,
            Timestamp.from(at),
            draftId,
            expectedVersion)
        == 1;
  }

  public void markPublished(String draftId, String approvalId, int contentVersion, Instant at) {
    jdbc.update(
        "UPDATE content_draft SET state = 'PUBLISHED', approval_id = ?, content_version = ?, version = version + 1,"
            + " updated_at = ? WHERE draft_id = ? AND state = 'SUBMITTED'",
        approvalId,
        contentVersion,
        Timestamp.from(at),
        draftId);
  }

  public void markRejected(String draftId, String approvalId, String problemsJson, Instant at) {
    jdbc.update(
        "UPDATE content_draft SET state = 'REJECTED', approval_id = ?, problems = ?::jsonb, version = version + 1,"
            + " updated_at = ? WHERE draft_id = ? AND state = 'SUBMITTED'",
        approvalId,
        problemsJson,
        Timestamp.from(at),
        draftId);
  }

  /** 원천 키의 DRAFT 초안을 상품에 연결. 바뀐 초안 수. */
  public int linkProduct(String sourceSystem, String sourceKey, String productId, Instant at) {
    return jdbc.update(
        "UPDATE content_draft SET product_id = ?, version = version + 1, updated_at = ?"
            + " WHERE source_system = ? AND source_key = ? AND state = 'DRAFT'"
            + " AND (product_id IS NULL OR product_id <> ?)",
        productId,
        Timestamp.from(at),
        sourceSystem,
        sourceKey,
        productId);
  }

  public Optional<DraftRow> find(String draftId) {
    return jdbc
        .query("SELECT * FROM content_draft WHERE draft_id = ?", (rs, n) -> draft(rs), draftId)
        .stream()
        .findFirst();
  }

  /** 원천 키의 열린(DRAFT·SUBMITTED) 초안. */
  public Optional<DraftRow> findOpen(String sourceSystem, String sourceKey) {
    return jdbc
        .query(
            "SELECT * FROM content_draft WHERE source_system = ? AND source_key = ? AND state IN ('DRAFT','SUBMITTED')",
            (rs, n) -> draft(rs),
            sourceSystem,
            sourceKey)
        .stream()
        .findFirst();
  }

  /** 원천 키의 가장 최근 게시 초안. */
  public Optional<DraftRow> findLatestPublished(String sourceSystem, String sourceKey) {
    return jdbc
        .query(
            "SELECT * FROM content_draft WHERE source_system = ? AND source_key = ? AND state = 'PUBLISHED'"
                + " ORDER BY content_version DESC NULLS LAST, updated_at DESC LIMIT 1",
            (rs, n) -> draft(rs),
            sourceSystem,
            sourceKey)
        .stream()
        .findFirst();
  }

  // ---------- 이미지 ----------

  public void insert(ImageRow r) {
    jdbc.update(
        "INSERT INTO content_image (image_id, draft_id, role, sort_order, bucket, object_key, content_type, width,"
            + " height, bytes, sha256, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.imageId(),
        r.draftId(),
        r.role(),
        r.sortOrder(),
        r.bucket(),
        r.objectKey(),
        r.contentType(),
        r.width(),
        r.height(),
        r.bytes(),
        r.sha256(),
        Timestamp.from(r.createdAt()));
  }

  public void deleteImage(String imageId) {
    jdbc.update("DELETE FROM content_image WHERE image_id = ?", imageId);
  }

  public List<ImageRow> images(String draftId) {
    return jdbc.query(
        "SELECT * FROM content_image WHERE draft_id = ? ORDER BY CASE role WHEN 'MAIN' THEN 0"
            + " WHEN 'DESCRIPTION' THEN 1 ELSE 2 END, sort_order",
        (rs, n) -> image(rs),
        draftId);
  }

  public Optional<ImageRow> image(String imageId) {
    return jdbc
        .query("SELECT * FROM content_image WHERE image_id = ?", (rs, n) -> image(rs), imageId)
        .stream()
        .findFirst();
  }

  /** 같은 내용이 이미 저장소에 있는지(어느 초안이든). */
  public Optional<ImageRow> anyWithSha(String sha256) {
    return jdbc
        .query(
            "SELECT * FROM content_image WHERE sha256 = ? ORDER BY created_at LIMIT 1",
            (rs, n) -> image(rs),
            sha256)
        .stream()
        .findFirst();
  }

  /**
   * 축소본을 아직 만들지 않은 이미지 내용(sha256 하나에 행 하나, 오래된 것부터 limit 개) — JPEG·PNG 만(WEBP 는 줄이지 못한다).
   * VariantPreparer 가 차례로 만든다(2026-11-20).
   */
  public List<ImageRow> imagesWithoutVariants(int limit) {
    return jdbc.query(
        "SELECT * FROM (SELECT DISTINCT ON (c.sha256) c.* FROM content_image c"
            + " WHERE c.content_type IN ('image/jpeg','image/png')"
            + " AND NOT EXISTS (SELECT 1 FROM content_image_variant v WHERE v.sha256 = c.sha256)"
            + " ORDER BY c.sha256, c.created_at) x ORDER BY x.created_at LIMIT ?",
        (rs, n) -> image(rs),
        limit);
  }

  /** 그 내용의 축소본을 다 만들었다(이미 있으면 그대로). */
  public void markVariantsPrepared(String sha256, Instant at) {
    jdbc.update(
        "INSERT INTO content_image_variant (sha256, prepared_at) VALUES (?, ?) ON CONFLICT (sha256) DO NOTHING",
        sha256,
        Timestamp.from(at));
  }

  public long imageCount() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM content_image", Long.class);
    return n == null ? 0 : n;
  }

  // ---------- 배치 ----------

  public void insert(BatchRow b) {
    jdbc.update(
        "INSERT INTO content_import_batch (batch_id, format, source_system, source_name, bundle_sha256, state,"
            + " product_count, created_count, updated_count, unchanged_count, image_count, uploaded_image_count,"
            + " created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        b.batchId(),
        b.format(),
        b.sourceSystem(),
        b.sourceName(),
        b.bundleSha256(),
        b.state(),
        b.productCount(),
        b.createdCount(),
        b.updatedCount(),
        b.unchangedCount(),
        b.imageCount(),
        b.uploadedImageCount(),
        b.createdBy(),
        Timestamp.from(b.createdAt()));
  }

  public void updateBatchCounts(
      String batchId, int products, int created, int updated, int unchanged, int images, int up) {
    jdbc.update(
        "UPDATE content_import_batch SET product_count = ?, created_count = ?, updated_count = ?,"
            + " unchanged_count = ?, image_count = ?, uploaded_image_count = ? WHERE batch_id = ?",
        products,
        created,
        updated,
        unchanged,
        images,
        up,
        batchId);
  }

  public void updateBatchState(String batchId, String state) {
    jdbc.update("UPDATE content_import_batch SET state = ? WHERE batch_id = ?", state, batchId);
  }

  public Optional<BatchRow> findBatch(String batchId) {
    return jdbc
        .query(
            "SELECT * FROM content_import_batch WHERE batch_id = ?",
            (rs, n) ->
                new BatchRow(
                    rs.getString("batch_id"),
                    rs.getString("format"),
                    rs.getString("source_system"),
                    rs.getString("source_name"),
                    rs.getString("bundle_sha256"),
                    rs.getString("state"),
                    rs.getInt("product_count"),
                    rs.getInt("created_count"),
                    rs.getInt("updated_count"),
                    rs.getInt("unchanged_count"),
                    rs.getInt("image_count"),
                    rs.getInt("uploaded_image_count"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant()),
            batchId)
        .stream()
        .findFirst();
  }

  public void insert(ItemRow i) {
    jdbc.update(
        "INSERT INTO content_import_batch_item (batch_id, source_key, draft_id, outcome) VALUES (?,?,?,?)"
            + " ON CONFLICT (batch_id, source_key) DO UPDATE SET draft_id = EXCLUDED.draft_id, outcome = EXCLUDED.outcome",
        i.batchId(),
        i.sourceKey(),
        i.draftId(),
        i.outcome());
  }

  public List<ItemRow> items(String batchId) {
    return jdbc.query(
        "SELECT * FROM content_import_batch_item WHERE batch_id = ? ORDER BY source_key",
        (rs, n) ->
            new ItemRow(
                rs.getString("batch_id"),
                rs.getString("source_key"),
                rs.getString("draft_id"),
                rs.getString("outcome")),
        batchId);
  }

  private static DraftRow draft(ResultSet rs) throws SQLException {
    return new DraftRow(
        rs.getString("draft_id"),
        rs.getString("source_system"),
        rs.getString("source_key"),
        rs.getString("product_id"),
        rs.getString("state"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("storage_guide"),
        rs.getString("return_guide"),
        rs.getString("notices"),
        rs.getString("options"),
        rs.getString("content_hash"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        (Integer) rs.getObject("content_version"),
        rs.getString("problems"),
        rs.getInt("version"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static ImageRow image(ResultSet rs) throws SQLException {
    return new ImageRow(
        rs.getString("image_id"),
        rs.getString("draft_id"),
        rs.getString("role"),
        rs.getInt("sort_order"),
        rs.getString("bucket"),
        rs.getString("object_key"),
        rs.getString("content_type"),
        (Integer) rs.getObject("width"),
        (Integer) rs.getObject("height"),
        rs.getLong("bytes"),
        rs.getString("sha256"),
        rs.getTimestamp("created_at").toInstant());
  }

  /** 0.4.0 PCT-1: 목록 한 줄 — 대표 이미지는 MAIN 첫째. */
  public record SummaryRow(
      String draftId,
      @Nullable String productId,
      String state,
      @Nullable String name,
      @Nullable String imageId,
      @Nullable String approvalId,
      Instant updatedAt,
      int version) {}

  /** 최근 바뀐 순(updated_at·draft_id 내림차순) keyset 목록. */
  public List<SummaryRow> summaries(
      List<String> states,
      @Nullable String productId,
      @Nullable Instant afterUpdatedAt,
      @Nullable String afterDraftId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.draft_id, d.product_id, d.state, d.name, d.approval_id, d.updated_at, d.version,"
                + " (SELECT i.image_id FROM content_image i WHERE i.draft_id = d.draft_id AND i.role = 'MAIN'"
                + " ORDER BY i.sort_order LIMIT 1) AS image_id FROM content_draft d WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (!states.isEmpty()) {
      sql.append(" AND d.state IN (")
          .append(String.join(",", java.util.Collections.nCopies(states.size(), "?")))
          .append(")");
      args.addAll(states);
    }
    if (productId != null) {
      sql.append(" AND d.product_id = ?");
      args.add(productId);
    }
    if (afterUpdatedAt != null && afterDraftId != null) {
      sql.append(" AND (d.updated_at, d.draft_id) < (?, ?)");
      args.add(java.sql.Timestamp.from(afterUpdatedAt));
      args.add(afterDraftId);
    }
    sql.append(" ORDER BY d.updated_at DESC, d.draft_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, n) ->
            new SummaryRow(
                rs.getString("draft_id"),
                rs.getString("product_id"),
                rs.getString("state"),
                rs.getString("name"),
                rs.getString("image_id"),
                rs.getString("approval_id"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("version")),
        args.toArray());
  }

  /** 0.4.0 PCT-1 CD-2: 상태별 수(없는 상태는 빠짐 — 호출자가 0 으로 채운다). */
  public java.util.Map<String, Long> countByState(@Nullable String productId) {
    java.util.Map<String, Long> out = new java.util.HashMap<>();
    String sql =
        "SELECT state, count(*) AS n FROM content_draft"
            + (productId == null ? "" : " WHERE product_id = ?")
            + " GROUP BY state";
    Object[] args = productId == null ? new Object[0] : new Object[] {productId};
    jdbc.query(
        sql,
        rs -> {
          out.put(rs.getString("state"), rs.getLong("n"));
        },
        args);
    return out;
  }
}
