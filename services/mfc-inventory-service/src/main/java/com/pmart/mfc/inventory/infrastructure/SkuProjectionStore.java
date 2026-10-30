package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.domain.SkuProjection.SkuState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SKU 투영 저장(JDBC). 오래된 상품 버전은 {@code ON CONFLICT ... WHERE} 조건으로 막는다(ProductVersionGate 와 같은 규칙,
 * 경합에도 원자적).
 */
@Repository
public class SkuProjectionStore {

  private static final String COLUMNS =
      "sku, product_id, base_product_id, unit_type, units_per_sku, weight_g, volume_ml, storage_type,"
          + " alcohol, state, product_version, updated_at";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public SkuProjectionStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /** SkuIssued: 식별 열만. 없으면 ACTIVE 로 만들고, 저장 버전보다 새 버전일 때만 식별 열을 갱신한다. */
  public int upsertIssued(
      String sku,
      @Nullable String productId,
      @Nullable String baseProductId,
      int productVersion,
      Instant at) {
    return jdbc.update(
        "INSERT INTO sku_projection (sku, product_id, base_product_id, unit_type, state, product_version,"
            + " updated_at) VALUES (?,?,?,?,'ACTIVE',?,?)"
            + " ON CONFLICT (sku) DO UPDATE SET product_id = EXCLUDED.product_id,"
            + " base_product_id = EXCLUDED.base_product_id, product_version = EXCLUDED.product_version,"
            + " updated_at = EXCLUDED.updated_at"
            + " WHERE sku_projection.product_version < EXCLUDED.product_version",
        sku,
        productId,
        baseProductId,
        SkuProjection.unitTypeOf(sku),
        productVersion,
        Timestamp.from(at));
  }

  /** ProductVersionPublished: 전체 스냅샷. 저장 버전 ≤ 들어온 버전이면 덮어쓴다. */
  public int upsertSnapshot(SkuProjection p) {
    return jdbc.update(
        "INSERT INTO sku_projection ("
            + COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (sku) DO UPDATE SET product_id = EXCLUDED.product_id,"
            + " base_product_id = EXCLUDED.base_product_id, unit_type = EXCLUDED.unit_type,"
            + " units_per_sku = EXCLUDED.units_per_sku, weight_g = EXCLUDED.weight_g,"
            + " volume_ml = EXCLUDED.volume_ml, storage_type = EXCLUDED.storage_type,"
            + " alcohol = EXCLUDED.alcohol, state = EXCLUDED.state,"
            + " product_version = EXCLUDED.product_version, updated_at = EXCLUDED.updated_at"
            + " WHERE sku_projection.product_version <= EXCLUDED.product_version",
        p.sku(),
        p.productId(),
        p.baseProductId(),
        p.unitType(),
        p.unitsPerSku(),
        p.weightG(),
        p.volumeMl(),
        p.storageType(),
        p.alcohol(),
        p.state().name(),
        p.productVersion(),
        Timestamp.from(p.updatedAt()));
  }

  public Optional<SkuProjection> find(String sku) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM sku_projection WHERE sku = ?",
            SkuProjectionStore::map,
            sku)
        .stream()
        .findFirst();
  }

  public Map<String, SkuProjection> findAll(Collection<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    List<SkuProjection> rows =
        named.query(
            "SELECT " + COLUMNS + " FROM sku_projection WHERE sku IN (:skus)",
            Map.of("skus", skus),
            (rs, i) -> map(rs, i));
    return rows.stream().collect(Collectors.toMap(SkuProjection::sku, Function.identity()));
  }

  static SkuProjection map(ResultSet rs, int i) throws SQLException {
    return new SkuProjection(
        rs.getString("sku"),
        rs.getString("product_id"),
        rs.getString("base_product_id"),
        rs.getString("unit_type"),
        rs.getObject("units_per_sku", Integer.class),
        rs.getObject("weight_g", Integer.class),
        rs.getObject("volume_ml", Integer.class),
        rs.getString("storage_type"),
        rs.getObject("alcohol", Boolean.class),
        SkuState.valueOf(rs.getString("state")),
        rs.getInt("product_version"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
