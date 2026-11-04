package com.pmart.product.infrastructure;

import com.pmart.product.domain.BarcodeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 상품·SKU·바코드·물류 규격·버전 스냅샷 저장(JDBC). 승인 반영 경로에서만 쓴다(ADR-0014). */
@Repository
public class CatalogStore {

  public record ProductRow(
      String productId,
      String baseProductId,
      String internalName,
      String primaryCategoryId,
      String saleState,
      int currentVersion,
      int rowVersion,
      Instant createdAt,
      Instant updatedAt) {}

  public record SkuRow(
      String sku,
      long serialNo,
      String unitType,
      String productId,
      String baseProductId,
      int unitsPerSku,
      int weightG,
      @Nullable Integer volumeMl,
      String storageType,
      boolean alcohol,
      String state,
      String issuedApprovalId,
      Instant issuedAt) {}

  public record LogisticsRow(
      @Nullable Integer unitsPerBox,
      @Nullable Integer boxesPerPallet,
      @Nullable Integer ti,
      @Nullable Integer hi) {}

  public record SourceLinkRow(
      String sourceSystem, String sourceKey, String productId, String skus, String lastDraftId) {}

  private final JdbcTemplate jdbc;

  public CatalogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 기초상품 ----------

  public boolean baseProductExists(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM base_product WHERE base_product_id = ?)",
            Boolean.class,
            id));
  }

  public Optional<String> baseProductByKey(String key) {
    return jdbc
        .queryForList(
            "SELECT base_product_id FROM base_product WHERE base_product_key = ?",
            String.class,
            key)
        .stream()
        .findFirst();
  }

  public String insertBaseProduct(@Nullable String key, String name, Instant at) {
    String id = "BP-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO base_product (base_product_id, base_product_key, internal_name, created_at) VALUES (?,?,?,?)",
        id,
        key,
        name,
        Timestamp.from(at));
    return id;
  }

  // ---------- 상품 ----------

  public void insertProduct(ProductRow p) {
    jdbc.update(
        "INSERT INTO product (product_id, base_product_id, internal_name, primary_category_id, sale_state,"
            + " current_version, row_version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        p.productId(),
        p.baseProductId(),
        p.internalName(),
        p.primaryCategoryId(),
        p.saleState(),
        p.currentVersion(),
        p.rowVersion(),
        Timestamp.from(p.createdAt()),
        Timestamp.from(p.updatedAt()));
  }

  /** 새 승인 버전 반영(버전 조건부). */
  public boolean updateProduct(
      String productId,
      int expectedRowVersion,
      String name,
      String primaryCategoryId,
      String saleState,
      int newVersion,
      Instant at) {
    return jdbc.update(
            "UPDATE product SET internal_name = ?, primary_category_id = ?, sale_state = ?, current_version = ?,"
                + " row_version = row_version + 1, updated_at = ? WHERE product_id = ? AND row_version = ?",
            name,
            primaryCategoryId,
            saleState,
            newVersion,
            Timestamp.from(at),
            productId,
            expectedRowVersion)
        == 1;
  }

  public Optional<ProductRow> findProduct(String productId) {
    return jdbc
        .query("SELECT * FROM product WHERE product_id = ?", (rs, n) -> product(rs), productId)
        .stream()
        .findFirst();
  }

  /** 목록(키셋 페이지: product_id 오름차순). categoryId 는 노출 카테고리 어느 것이든. */
  public List<ProductRow> listProducts(
      @Nullable String categoryId,
      @Nullable String saleState,
      @Nullable String afterId,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT p.* FROM product p WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (categoryId != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM product_category pc WHERE pc.product_id = p.product_id AND pc.category_id = ?)");
      args.add(categoryId);
    }
    if (saleState != null) {
      sql.append(" AND p.sale_state = ?");
      args.add(saleState);
    }
    if (afterId != null) {
      sql.append(" AND p.product_id > ?");
      args.add(afterId);
    }
    sql.append(" ORDER BY p.product_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, n) -> product(rs), args.toArray());
  }

  public void replaceCategories(String productId, List<String> categoryIds) {
    jdbc.update("DELETE FROM product_category WHERE product_id = ?", productId);
    for (int i = 0; i < categoryIds.size(); i++) {
      jdbc.update(
          "INSERT INTO product_category (product_id, category_id, is_primary) VALUES (?,?,?)",
          productId,
          categoryIds.get(i),
          i == 0);
    }
  }

  public List<String> categoriesOf(String productId) {
    return jdbc.queryForList(
        "SELECT category_id FROM product_category WHERE product_id = ? ORDER BY is_primary DESC, category_id",
        String.class,
        productId);
  }

  /** 상품의 승인 속성(필터 옵션) — (그룹 id, 옵션 이름), 그룹·옵션 순. 검색 필터용으로 사건에 싣는다. */
  public List<String[]> filterOptionsOf(String productId) {
    return jdbc.query(
        "SELECT filter_group_id, option_name FROM product_filter_option WHERE product_id = ?"
            + " ORDER BY filter_group_id, option_name",
        (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
        productId);
  }

  public void replaceFilterOptions(String productId, List<String[]> groupAndOption) {
    jdbc.update("DELETE FROM product_filter_option WHERE product_id = ?", productId);
    for (String[] go : groupAndOption) {
      jdbc.update(
          "INSERT INTO product_filter_option (product_id, filter_group_id, option_name) VALUES (?,?,?)"
              + " ON CONFLICT DO NOTHING",
          productId,
          go[0],
          go[1]);
    }
  }

  // ---------- SKU ----------

  /** E/B 공유 전역 일련번호. 롤백돼도 번호는 돌아오지 않는다(재사용 금지). */
  public long nextSerial() {
    Long v = jdbc.queryForObject("SELECT nextval('sku_serial_seq')", Long.class);
    return v == null ? 0 : v;
  }

  public void insertSku(SkuRow s) {
    jdbc.update(
        "INSERT INTO sku (sku, serial_no, unit_type, product_id, base_product_id, units_per_sku, weight_g, volume_ml,"
            + " storage_type, alcohol, state, issued_approval_id, issued_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        s.sku(),
        s.serialNo(),
        s.unitType(),
        s.productId(),
        s.baseProductId(),
        s.unitsPerSku(),
        s.weightG(),
        s.volumeMl(),
        s.storageType(),
        s.alcohol(),
        s.state(),
        s.issuedApprovalId(),
        Timestamp.from(s.issuedAt()));
  }

  /** 승인된 규격 변경(중량·용량·보관 온도·주류). E/B·구성 낱개 수는 SKU 정체성이라 바꾸지 않는다. */
  public void updateSkuAttributes(
      String sku, int weightG, @Nullable Integer volumeMl, String storageType, boolean alcohol) {
    jdbc.update(
        "UPDATE sku SET weight_g = ?, volume_ml = ?, storage_type = ?, alcohol = ? WHERE sku = ?",
        weightG,
        volumeMl,
        storageType,
        alcohol,
        sku);
  }

  public Optional<SkuRow> findSku(String sku) {
    return jdbc.query("SELECT * FROM sku WHERE sku = ?", (rs, n) -> sku(rs), sku).stream()
        .findFirst();
  }

  public List<SkuRow> skusOf(String productId) {
    return jdbc.query(
        "SELECT * FROM sku WHERE product_id = ? ORDER BY serial_no", (rs, n) -> sku(rs), productId);
  }

  public List<SkuRow> findSkus(List<String> skus) {
    if (skus.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", java.util.Collections.nCopies(skus.size(), "?"));
    return jdbc.query(
        "SELECT * FROM sku WHERE sku IN (" + in + ") ORDER BY serial_no",
        (rs, n) -> sku(rs),
        skus.toArray());
  }

  // ---------- 바코드 ----------

  public Optional<String> activeBarcodeOwner(String barcode) {
    return jdbc
        .queryForList(
            "SELECT sku FROM sku_barcode WHERE barcode = ? AND state = 'ACTIVE'",
            String.class,
            barcode)
        .stream()
        .findFirst();
  }

  public List<String> activeBarcodes(String sku) {
    return jdbc.queryForList(
        "SELECT barcode FROM sku_barcode WHERE sku = ? AND state = 'ACTIVE' ORDER BY valid_from, barcode",
        String.class,
        sku);
  }

  public void insertBarcode(String barcode, String sku, String approvalId, Instant at) {
    jdbc.update(
        "INSERT INTO sku_barcode (barcode_id, barcode, sku, barcode_type, state, valid_from, approval_id)"
            + " VALUES (?,?,?,?, 'ACTIVE', ?, ?)",
        "BC-" + UUID.randomUUID(),
        barcode,
        sku,
        BarcodeType.classify(barcode).name(),
        Timestamp.from(at),
        approvalId);
  }

  /** 승인된 바코드 목록에서 빠진 활성 바코드는 RETIRED(이력 보존, 삭제 없음). */
  public void retireBarcode(String barcode, String sku, Instant at) {
    jdbc.update(
        "UPDATE sku_barcode SET state = 'RETIRED', valid_to = ? WHERE barcode = ? AND sku = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        barcode,
        sku);
  }

  // ---------- 물류 규격 ----------

  public void insertLogistics(String sku, LogisticsRow l, String approvalId, Instant at) {
    Integer next =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(spec_version), 0) + 1 FROM sku_logistics_spec WHERE sku = ?",
            Integer.class,
            sku);
    jdbc.update(
        "INSERT INTO sku_logistics_spec (sku, spec_version, units_per_box, boxes_per_pallet, ti, hi, approval_id,"
            + " effective_from) VALUES (?,?,?,?,?,?,?,?)",
        sku,
        next,
        l.unitsPerBox(),
        l.boxesPerPallet(),
        l.ti(),
        l.hi(),
        approvalId,
        Timestamp.from(at));
  }

  public Optional<LogisticsRow> latestLogistics(String sku) {
    return jdbc
        .query(
            "SELECT * FROM sku_logistics_spec WHERE sku = ? ORDER BY spec_version DESC LIMIT 1",
            (rs, n) ->
                new LogisticsRow(
                    (Integer) rs.getObject("units_per_box"),
                    (Integer) rs.getObject("boxes_per_pallet"),
                    (Integer) rs.getObject("ti"),
                    (Integer) rs.getObject("hi")),
            sku)
        .stream()
        .findFirst();
  }

  // ---------- 버전 스냅샷 ----------

  public void insertVersion(
      String productId, int version, String approvalId, String snapshotJson, Instant at) {
    jdbc.update(
        "INSERT INTO product_version (product_id, version, approval_id, snapshot, published_at) VALUES (?,?,?,?::jsonb,?)",
        productId,
        version,
        approvalId,
        snapshotJson,
        Timestamp.from(at));
  }

  public Optional<String> versionSnapshot(String productId, int version) {
    return jdbc
        .queryForList(
            "SELECT snapshot::text FROM product_version WHERE product_id = ? AND version = ?",
            String.class,
            productId,
            version)
        .stream()
        .findFirst();
  }

  // ---------- 원천 키 연결 ----------

  public Optional<SourceLinkRow> sourceLink(String system, String key) {
    return jdbc
        .query(
            "SELECT * FROM product_source_link WHERE source_system = ? AND source_key = ?",
            (rs, n) ->
                new SourceLinkRow(
                    rs.getString("source_system"),
                    rs.getString("source_key"),
                    rs.getString("product_id"),
                    rs.getString("skus"),
                    rs.getString("last_draft_id")),
            system,
            key)
        .stream()
        .findFirst();
  }

  /** 원천 키 여러 개의 연결(발급된 것만). 순서는 원천 키 순. */
  public List<SourceLinkRow> sourceLinks(String system, List<String> keys) {
    if (keys.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT * FROM product_source_link WHERE source_system = ? AND source_key = ANY(?)"
                      + " ORDER BY source_key");
          ps.setString(1, system);
          ps.setArray(2, con.createArrayOf("varchar", keys.toArray()));
          return ps;
        },
        (rs, n) ->
            new SourceLinkRow(
                rs.getString("source_system"),
                rs.getString("source_key"),
                rs.getString("product_id"),
                rs.getString("skus"),
                rs.getString("last_draft_id")));
  }

  public void upsertSourceLink(
      String system, String key, String productId, String skusJson, String draftId, Instant at) {
    jdbc.update(
        "INSERT INTO product_source_link (source_system, source_key, product_id, skus, last_draft_id, updated_at)"
            + " VALUES (?,?,?,?::jsonb,?,?) ON CONFLICT (source_system, source_key) DO UPDATE SET"
            + " product_id = EXCLUDED.product_id, skus = EXCLUDED.skus, last_draft_id = EXCLUDED.last_draft_id,"
            + " updated_at = EXCLUDED.updated_at",
        system,
        key,
        productId,
        skusJson,
        draftId,
        Timestamp.from(at));
  }

  // ---------- 승인 반영 기록 ----------

  public boolean approvalApplied(String approvalId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM approval_application WHERE approval_id = ?)",
            Boolean.class,
            approvalId));
  }

  public void recordApplication(
      String approvalId, String targetRef, String result, String detailJson, Instant at) {
    jdbc.update(
        "INSERT INTO approval_application (approval_id, target_ref, result, detail, applied_at) VALUES (?,?,?,?::jsonb,?)",
        approvalId,
        targetRef,
        result,
        detailJson,
        Timestamp.from(at));
  }

  private static ProductRow product(ResultSet rs) throws SQLException {
    return new ProductRow(
        rs.getString("product_id"),
        rs.getString("base_product_id"),
        rs.getString("internal_name"),
        rs.getString("primary_category_id"),
        rs.getString("sale_state"),
        rs.getInt("current_version"),
        rs.getInt("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static SkuRow sku(ResultSet rs) throws SQLException {
    return new SkuRow(
        rs.getString("sku"),
        rs.getLong("serial_no"),
        rs.getString("unit_type"),
        rs.getString("product_id"),
        rs.getString("base_product_id"),
        rs.getInt("units_per_sku"),
        rs.getInt("weight_g"),
        (Integer) rs.getObject("volume_ml"),
        rs.getString("storage_type"),
        rs.getBoolean("alcohol"),
        rs.getString("state"),
        rs.getString("issued_approval_id"),
        rs.getTimestamp("issued_at").toInstant());
  }
}
