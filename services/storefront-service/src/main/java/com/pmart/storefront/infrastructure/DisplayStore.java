package com.pmart.storefront.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 진열 투영(JDBC). 갱신은 모두 "더 새 버전·시각일 때만"(ON CONFLICT … WHERE) — 늦게 온 옛 사건이 덮어쓰지 않는다. 가격은 SKU 별로 두고 조회
 * 때 대표 SKU 로 붙인다(가격·상품 사건 순서와 무관).
 */
@Repository
public class DisplayStore {

  public record Item(
      String productId,
      String name,
      @Nullable String imageUrl,
      @Nullable String representativeSku,
      @Nullable Long basePrice,
      @Nullable Integer priceVersion,
      String saleState) {}

  private final JdbcTemplate jdbc;

  public DisplayStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertProduct(
      String productId,
      int version,
      @Nullable String sku,
      String saleState,
      Instant at,
      Instant now) {
    jdbc.update(
        "INSERT INTO display_product (product_id, product_version, representative_sku, sale_state,"
            + " sale_state_at, updated_at) VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET product_version = EXCLUDED.product_version,"
            + " representative_sku = EXCLUDED.representative_sku,"
            + " sale_state = CASE WHEN display_product.sale_state_at IS NULL"
            + "   OR EXCLUDED.sale_state_at >= display_product.sale_state_at"
            + "   THEN EXCLUDED.sale_state ELSE display_product.sale_state END,"
            + " sale_state_at = GREATEST(display_product.sale_state_at, EXCLUDED.sale_state_at),"
            + " updated_at = EXCLUDED.updated_at"
            + " WHERE display_product.product_version IS NULL"
            + "   OR EXCLUDED.product_version > display_product.product_version",
        productId,
        version,
        sku,
        saleState,
        ts(at),
        ts(now));
  }

  public void upsertContent(
      String productId,
      int contentVersion,
      String name,
      @Nullable String imageUrl,
      Instant publishedAt,
      Instant now) {
    jdbc.update(
        "INSERT INTO display_product (product_id, name, image_url, content_version,"
            + " content_published_at, updated_at) VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET name = EXCLUDED.name,"
            + " image_url = EXCLUDED.image_url, content_version = EXCLUDED.content_version,"
            + " content_published_at = COALESCE(display_product.content_published_at,"
            + "   EXCLUDED.content_published_at),"
            + " updated_at = EXCLUDED.updated_at"
            + " WHERE display_product.content_version IS NULL"
            + "   OR EXCLUDED.content_version > display_product.content_version",
        productId,
        name,
        imageUrl,
        contentVersion,
        ts(publishedAt),
        ts(now));
  }

  public void upsertSaleState(String productId, String saleState, Instant at, Instant now) {
    jdbc.update(
        "INSERT INTO display_product (product_id, sale_state, sale_state_at, updated_at)"
            + " VALUES (?,?,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET sale_state = EXCLUDED.sale_state,"
            + " sale_state_at = EXCLUDED.sale_state_at, updated_at = EXCLUDED.updated_at"
            + " WHERE display_product.sale_state_at IS NULL"
            + "   OR EXCLUDED.sale_state_at >= display_product.sale_state_at",
        productId,
        saleState,
        ts(at),
        ts(now));
  }

  public void upsertPrice(String sku, long price, int priceVersion, @Nullable Instant at) {
    jdbc.update(
        "INSERT INTO display_sku_price (sku, price, price_version, effective_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (sku) DO UPDATE SET price = EXCLUDED.price,"
            + " price_version = EXCLUDED.price_version, effective_at = EXCLUDED.effective_at"
            + " WHERE EXCLUDED.price_version > display_sku_price.price_version",
        sku,
        price,
        priceVersion,
        ts(at));
  }

  private static final String DISPLAYABLE =
      " FROM display_product p LEFT JOIN display_sku_price s ON s.sku = p.representative_sku"
          + " WHERE p.name IS NOT NULL AND p.product_version IS NOT NULL"
          + " AND COALESCE(p.sale_state, 'ON_SALE') <> 'SALE_STOPPED'";

  /** 진열 가능한 상품(게시 콘텐츠·상품 버전 있음, 판매중지 아님) 최신 게시 순. */
  public List<Item> latest(int offset, int limit) {
    return jdbc.query(
        "SELECT p.product_id, p.name, p.image_url, p.representative_sku, s.price, s.price_version,"
            + " COALESCE(p.sale_state, 'ON_SALE') AS sale_state"
            + DISPLAYABLE
            + " ORDER BY p.content_published_at DESC NULLS LAST, p.product_id OFFSET ? LIMIT ?",
        (rs, i) ->
            new Item(
                rs.getString("product_id"),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getString("representative_sku"),
                rs.getObject("price", Long.class),
                rs.getObject("price_version", Integer.class),
                rs.getString("sale_state")),
        offset,
        limit);
  }

  public int countDisplayable() {
    Integer n = jdbc.queryForObject("SELECT COUNT(*)" + DISPLAYABLE, Integer.class);
    return n == null ? 0 : n;
  }

  /** 투영의 마지막 갱신 시각(asOf). 비어 있으면 null. */
  public @Nullable Instant lastUpdated() {
    Timestamp t =
        jdbc.queryForObject("SELECT MAX(updated_at) FROM display_product", Timestamp.class);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
