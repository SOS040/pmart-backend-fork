package com.pmart.pricing.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SKU 투영 저장(JDBC). 무효 SKU 검사에만 쓴다. 오래된 상품 버전은 {@code ON CONFLICT ... WHERE} 로 막는다(mfc-inventory 와
 * 같은 규칙: 스냅샷은 저장 버전 이하일 때, SkuIssued 는 저장 버전 미만일 때만 갱신).
 */
@Repository
public class SkuProjectionStore {

  private final JdbcTemplate jdbc;

  public SkuProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertIssued(String sku, @Nullable String productId, int productVersion, Instant at) {
    jdbc.update(
        "INSERT INTO sku_projection (sku, product_id, state, product_version, updated_at)"
            + " VALUES (?,?,'ACTIVE',?,?) ON CONFLICT (sku) DO UPDATE SET product_id ="
            + " EXCLUDED.product_id, product_version = EXCLUDED.product_version, updated_at ="
            + " EXCLUDED.updated_at WHERE sku_projection.product_version < EXCLUDED.product_version",
        sku,
        productId,
        productVersion,
        Timestamp.from(at));
  }

  public void upsertSnapshot(
      String sku, @Nullable String productId, String state, int productVersion, Instant at) {
    jdbc.update(
        "INSERT INTO sku_projection (sku, product_id, state, product_version, updated_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (sku) DO UPDATE SET product_id = EXCLUDED.product_id,"
            + " state = EXCLUDED.state, product_version = EXCLUDED.product_version, updated_at ="
            + " EXCLUDED.updated_at WHERE sku_projection.product_version <= EXCLUDED.product_version",
        sku,
        productId,
        state,
        productVersion,
        Timestamp.from(at));
  }

  public boolean exists(String sku) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sku_projection WHERE sku = ?", Integer.class, sku);
    return n != null && n > 0;
  }
}
