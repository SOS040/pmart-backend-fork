package com.pmart.storefront.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 진열 편성 초안·게시 버전·배너 이미지(V9, 2026-11-20 ADR-0075). 본문은 jsonb 문자열 그대로. */
@Repository
public class LayoutStore {

  public record DraftRow(
      String draftId,
      String state,
      String body,
      int version,
      @Nullable String draftHash,
      @Nullable String approvalId,
      @Nullable Integer layoutVersion,
      @Nullable String rejectionReason,
      String createdBy,
      Instant createdAt,
      @Nullable Instant updatedAt) {}

  public record VersionRow(
      int layoutVersion, String draftId, String approvalId, String body, Instant publishedAt) {}

  public record ImageRow(
      String imageId,
      String objectKey,
      String contentType,
      long sizeBytes,
      String sha256,
      @Nullable Integer width,
      @Nullable Integer height,
      String uploadedBy,
      Instant uploadedAt) {}

  private final JdbcTemplate jdbc;

  public LayoutStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 초안 ----------

  public void insertDraft(String draftId, String body, String by, Instant at) {
    jdbc.update(
        "INSERT INTO layout_draft (draft_id, state, body, version, created_by, created_at)"
            + " VALUES (?, 'DRAFT', ?::jsonb, 1, ?, ?)",
        draftId,
        body,
        by,
        Timestamp.from(at));
  }

  public Optional<DraftRow> draft(String draftId) {
    return jdbc
        .query("SELECT * FROM layout_draft WHERE draft_id = ?", LayoutStore::draftRow, draftId)
        .stream()
        .findFirst();
  }

  public Optional<DraftRow> lockDraft(String draftId) {
    return jdbc
        .query(
            "SELECT * FROM layout_draft WHERE draft_id = ? FOR UPDATE",
            LayoutStore::draftRow,
            draftId)
        .stream()
        .findFirst();
  }

  public List<DraftRow> drafts(@Nullable String state, int limit) {
    return state == null
        ? jdbc.query(
            "SELECT * FROM layout_draft ORDER BY created_at DESC LIMIT ?",
            LayoutStore::draftRow,
            limit)
        : jdbc.query(
            "SELECT * FROM layout_draft WHERE state = ? ORDER BY created_at DESC LIMIT ?",
            LayoutStore::draftRow,
            state,
            limit);
  }

  /** 고치기 — DRAFT·REJECTED 만, 버전 +1, REJECTED 면 DRAFT 로. */
  public int updateDraft(String draftId, String body, int expectedVersion, Instant at) {
    return jdbc.update(
        "UPDATE layout_draft SET body = ?::jsonb, version = version + 1, state = 'DRAFT', rejection_reason = NULL,"
            + " draft_hash = NULL, updated_at = ? WHERE draft_id = ? AND version = ? AND state IN ('DRAFT','REJECTED')",
        body,
        Timestamp.from(at),
        draftId,
        expectedVersion);
  }

  public void submit(String draftId, String hash, Instant at) {
    jdbc.update(
        "UPDATE layout_draft SET state = 'SUBMITTED', draft_hash = ?, version = version + 1, updated_at = ?"
            + " WHERE draft_id = ? AND state IN ('DRAFT','REJECTED')",
        hash,
        Timestamp.from(at),
        draftId);
  }

  public void published(String draftId, String approvalId, int layoutVersion, Instant at) {
    jdbc.update(
        "UPDATE layout_draft SET state = 'PUBLISHED', approval_id = ?, layout_version = ?, version = version + 1,"
            + " updated_at = ? WHERE draft_id = ?",
        approvalId,
        layoutVersion,
        Timestamp.from(at),
        draftId);
  }

  public void rejected(String draftId, String approvalId, String reason, Instant at) {
    jdbc.update(
        "UPDATE layout_draft SET state = 'REJECTED', approval_id = ?, rejection_reason = ?, version = version + 1,"
            + " updated_at = ? WHERE draft_id = ? AND state = 'SUBMITTED'",
        approvalId,
        reason,
        Timestamp.from(at),
        draftId);
  }

  // ---------- 게시 버전 ----------

  public Optional<VersionRow> current() {
    return jdbc
        .query(
            "SELECT * FROM layout_version ORDER BY layout_version DESC LIMIT 1",
            (rs, i) ->
                new VersionRow(
                    rs.getInt("layout_version"),
                    rs.getString("draft_id"),
                    rs.getString("approval_id"),
                    rs.getString("body"),
                    rs.getTimestamp("published_at").toInstant()))
        .stream()
        .findFirst();
  }

  public boolean versionForApproval(String approvalId) {
    return !jdbc.queryForList("SELECT 1 FROM layout_version WHERE approval_id = ?", approvalId)
        .isEmpty();
  }

  /** 새 버전(지금 가장 큰 번호 + 1). */
  public int publish(String draftId, String approvalId, String body, Instant at) {
    Integer next =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(layout_version), 0) + 1 FROM layout_version", Integer.class);
    int v = next == null ? 1 : next;
    jdbc.update(
        "INSERT INTO layout_version (layout_version, draft_id, approval_id, body, published_at)"
            + " VALUES (?, ?, ?, ?::jsonb, ?)",
        v,
        draftId,
        approvalId,
        body,
        Timestamp.from(at));
    return v;
  }

  // ---------- 배너 이미지 ----------

  public void insertImage(ImageRow r) {
    jdbc.update(
        "INSERT INTO banner_image (image_id, object_key, content_type, size_bytes, sha256, width, height,"
            + " uploaded_by, uploaded_at) VALUES (?,?,?,?,?,?,?,?,?)",
        r.imageId(),
        r.objectKey(),
        r.contentType(),
        r.sizeBytes(),
        r.sha256(),
        r.width(),
        r.height(),
        r.uploadedBy(),
        Timestamp.from(r.uploadedAt()));
  }

  public Optional<ImageRow> image(String imageId) {
    return jdbc
        .query(
            "SELECT * FROM banner_image WHERE image_id = ?",
            (rs, i) ->
                new ImageRow(
                    rs.getString("image_id"),
                    rs.getString("object_key"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    rs.getObject("width", Integer.class),
                    rs.getObject("height", Integer.class),
                    rs.getString("uploaded_by"),
                    rs.getTimestamp("uploaded_at").toInstant()),
            imageId)
        .stream()
        .findFirst();
  }

  /** 게시된 어떤 버전에든 쓰인 이미지인가(공개 보기). */
  public boolean imagePublished(String imageId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM layout_version v, jsonb_array_elements(v.body -> 'banners') b"
                + " WHERE b ->> 'imageId' = ? LIMIT 1",
            imageId)
        .isEmpty();
  }

  private static DraftRow draftRow(ResultSet rs, int i) throws SQLException {
    return new DraftRow(
        rs.getString("draft_id"),
        rs.getString("state"),
        rs.getString("body"),
        rs.getInt("version"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        rs.getObject("layout_version", Integer.class),
        rs.getString("rejection_reason"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
  }
}
