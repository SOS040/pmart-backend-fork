package com.pmart.product.content.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 상품 투영(ProductVersionPublished 사본)·원천 키·게시본·승인 반영 기록. 게시본은 추가만 한다(불변). */
@Repository
public class CatalogStore {

  public record ProjectionRow(
      String productId,
      int latestProductVersion,
      @Nullable String saleState,
      @Nullable String primaryCategoryId,
      String categoryPaths,
      String skus,
      @Nullable Instant effectiveAt,
      Instant receivedAt) {}

  public record ContentRow(
      String productId,
      int contentVersion,
      @Nullable Integer productVersion,
      String draftId,
      String approvalId,
      String name,
      @Nullable String description,
      @Nullable String storageGuide,
      @Nullable String returnGuide,
      String notices,
      String options,
      String images,
      Instant publishedAt) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public CatalogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  // ---------- 투영 ----------

  /** 더 새 버전일 때만 덮어쓴다(재전달·순서 뒤바뀜에 안전). */
  public void upsertProjection(ProjectionRow p) {
    jdbc.update(
        "INSERT INTO product_projection (product_id, latest_product_version, sale_state, primary_category_id,"
            + " category_paths, skus, effective_at, received_at) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET latest_product_version = EXCLUDED.latest_product_version,"
            + " sale_state = EXCLUDED.sale_state, primary_category_id = EXCLUDED.primary_category_id,"
            + " category_paths = EXCLUDED.category_paths, skus = EXCLUDED.skus, effective_at = EXCLUDED.effective_at,"
            + " received_at = EXCLUDED.received_at"
            + " WHERE product_projection.latest_product_version < EXCLUDED.latest_product_version",
        p.productId(),
        p.latestProductVersion(),
        p.saleState(),
        p.primaryCategoryId(),
        p.categoryPaths(),
        p.skus(),
        p.effectiveAt() == null ? null : Timestamp.from(p.effectiveAt()),
        Timestamp.from(p.receivedAt()));
  }

  public Optional<ProjectionRow> projection(String productId) {
    return jdbc
        .query(
            "SELECT * FROM product_projection WHERE product_id = ?",
            (rs, n) -> projection(rs),
            productId)
        .stream()
        .findFirst();
  }

  public void upsertSourceRef(String system, String key, String productId, Instant at) {
    jdbc.update(
        "INSERT INTO product_source_ref (source_system, source_key, product_id, updated_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (source_system, source_key) DO UPDATE SET product_id = EXCLUDED.product_id,"
            + " updated_at = EXCLUDED.updated_at",
        system,
        key,
        productId,
        Timestamp.from(at));
  }

  public Optional<String> productBySource(String system, String key) {
    return jdbc
        .queryForList(
            "SELECT product_id FROM product_source_ref WHERE source_system = ? AND source_key = ?",
            String.class,
            system,
            key)
        .stream()
        .findFirst();
  }

  // ---------- 게시본 ----------

  public int maxContentVersion(String productId) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(content_version), 0) FROM product_content WHERE product_id = ?",
            Integer.class,
            productId);
    return v == null ? 0 : v;
  }

  public void insertContent(ContentRow c) {
    jdbc.update(
        "INSERT INTO product_content (product_id, content_version, product_version, draft_id, approval_id, name,"
            + " description, storage_guide, return_guide, notices, options, images, published_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)",
        c.productId(),
        c.contentVersion(),
        c.productVersion(),
        c.draftId(),
        c.approvalId(),
        c.name(),
        c.description(),
        c.storageGuide(),
        c.returnGuide(),
        c.notices(),
        c.options(),
        c.images(),
        Timestamp.from(c.publishedAt()));
  }

  public Optional<ContentRow> latestContent(String productId) {
    return jdbc
        .query(
            "SELECT * FROM product_content WHERE product_id = ? ORDER BY content_version DESC LIMIT 1",
            (rs, n) -> content(rs),
            productId)
        .stream()
        .findFirst();
  }

  public List<ContentRow> latestContents(Collection<String> productIds) {
    if (productIds.isEmpty()) {
      return List.of();
    }
    return named.query(
        "SELECT DISTINCT ON (product_id) * FROM product_content WHERE product_id IN (:ids)"
            + " ORDER BY product_id, content_version DESC",
        new MapSqlParameterSource("ids", productIds),
        (rs, n) -> content(rs));
  }

  public List<ProjectionRow> projections(Collection<String> productIds) {
    if (productIds.isEmpty()) {
      return List.of();
    }
    return named.query(
        "SELECT * FROM product_projection WHERE product_id IN (:ids)",
        new MapSqlParameterSource("ids", productIds),
        (rs, n) -> projection(rs));
  }

  // ---------- 승인 반영 기록 ----------

  public boolean approvalApplied(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_application WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n != null && n > 0;
  }

  public void recordApplication(
      String approvalId, String targetRef, String result, String detailJson, Instant at) {
    jdbc.update(
        "INSERT INTO approval_application (approval_id, target_ref, result, detail, applied_at)"
            + " VALUES (?,?,?,?::jsonb,?)",
        approvalId,
        targetRef,
        result,
        detailJson,
        Timestamp.from(at));
  }

  private static ProjectionRow projection(ResultSet rs) throws SQLException {
    Timestamp eff = rs.getTimestamp("effective_at");
    return new ProjectionRow(
        rs.getString("product_id"),
        rs.getInt("latest_product_version"),
        rs.getString("sale_state"),
        rs.getString("primary_category_id"),
        rs.getString("category_paths"),
        rs.getString("skus"),
        eff == null ? null : eff.toInstant(),
        rs.getTimestamp("received_at").toInstant());
  }

  private static ContentRow content(ResultSet rs) throws SQLException {
    return new ContentRow(
        rs.getString("product_id"),
        rs.getInt("content_version"),
        (Integer) rs.getObject("product_version"),
        rs.getString("draft_id"),
        rs.getString("approval_id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("storage_guide"),
        rs.getString("return_guide"),
        rs.getString("notices"),
        rs.getString("options"),
        rs.getString("images"),
        rs.getTimestamp("published_at").toInstant());
  }
}
