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
      String saleState,
      @Nullable Integer netContentAmount,
      @Nullable String netContentUnit,
      int reviewCount,
      @Nullable Double rating) {}

  private final JdbcTemplate jdbc;

  public DisplayStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertProduct(
      String productId,
      int version,
      @Nullable String sku,
      String saleState,
      @Nullable Integer saleStateRowVersion,
      Instant at,
      Instant now,
      @Nullable Integer netContentAmount,
      @Nullable String netContentUnit) {
    jdbc.update(
        "INSERT INTO display_product (product_id, product_version, representative_sku, sale_state,"
            + " sale_state_row_version, sale_state_at, updated_at, net_content_amount, net_content_unit)"
            + " VALUES (?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET product_version = EXCLUDED.product_version,"
            + " representative_sku = EXCLUDED.representative_sku,"
            + " net_content_amount = EXCLUDED.net_content_amount,"
            + " net_content_unit = EXCLUDED.net_content_unit,"
            + " sale_state = CASE WHEN (CASE WHEN EXCLUDED.sale_state_row_version IS NOT NULL"
            + "   THEN display_product.sale_state_row_version IS NULL"
            + "     OR EXCLUDED.sale_state_row_version > display_product.sale_state_row_version"
            + "   ELSE display_product.sale_state_row_version IS NULL AND (display_product.sale_state_at IS NULL"
            + "     OR EXCLUDED.sale_state_at >= display_product.sale_state_at) END)"
            + "   THEN EXCLUDED.sale_state ELSE display_product.sale_state END,"
            + " sale_state_at = CASE WHEN (CASE WHEN EXCLUDED.sale_state_row_version IS NOT NULL"
            + "   THEN display_product.sale_state_row_version IS NULL"
            + "     OR EXCLUDED.sale_state_row_version > display_product.sale_state_row_version"
            + "   ELSE display_product.sale_state_row_version IS NULL AND (display_product.sale_state_at IS NULL"
            + "     OR EXCLUDED.sale_state_at >= display_product.sale_state_at) END)"
            + "   THEN EXCLUDED.sale_state_at ELSE display_product.sale_state_at END,"
            + " sale_state_row_version = CASE WHEN (CASE WHEN EXCLUDED.sale_state_row_version IS NOT NULL"
            + "   THEN display_product.sale_state_row_version IS NULL"
            + "     OR EXCLUDED.sale_state_row_version > display_product.sale_state_row_version"
            + "   ELSE display_product.sale_state_row_version IS NULL AND (display_product.sale_state_at IS NULL"
            + "     OR EXCLUDED.sale_state_at >= display_product.sale_state_at) END)"
            + "   THEN COALESCE(EXCLUDED.sale_state_row_version, display_product.sale_state_row_version)"
            + "   ELSE display_product.sale_state_row_version END,"
            + " updated_at = EXCLUDED.updated_at"
            + " WHERE display_product.product_version IS NULL"
            + "   OR EXCLUDED.product_version > display_product.product_version",
        productId,
        version,
        sku,
        saleState,
        saleStateRowVersion,
        ts(at),
        ts(now),
        netContentAmount,
        netContentUnit);
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

  /** 판매 상태 사건. 상품 행 버전이 큰 것만(없으면 옛 사건 규칙 — 행 버전 기준 상태가 없을 때만 시각 비교). */
  public void upsertSaleState(
      String productId, String saleState, @Nullable Integer rowVersion, Instant at, Instant now) {
    jdbc.update(
        "INSERT INTO display_product (product_id, sale_state, sale_state_row_version, sale_state_at, updated_at)"
            + " VALUES (?,?,?,?,?)"
            + " ON CONFLICT (product_id) DO UPDATE SET sale_state = EXCLUDED.sale_state,"
            + " sale_state_row_version = COALESCE(EXCLUDED.sale_state_row_version, display_product.sale_state_row_version),"
            + " sale_state_at = EXCLUDED.sale_state_at, updated_at = EXCLUDED.updated_at"
            + " WHERE (CASE WHEN EXCLUDED.sale_state_row_version IS NOT NULL"
            + "   THEN display_product.sale_state_row_version IS NULL"
            + "     OR EXCLUDED.sale_state_row_version > display_product.sale_state_row_version"
            + "   ELSE display_product.sale_state_row_version IS NULL AND (display_product.sale_state_at IS NULL"
            + "     OR EXCLUDED.sale_state_at >= display_product.sale_state_at) END)",
        productId,
        saleState,
        rowVersion,
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

  /** 리뷰 별점 사본(V6, BR-63) — 리뷰 버전이 큰 사건만. 반영했으면 1. */
  public int upsertReviewRating(
      String reviewId, String productId, int rating, boolean deleted, int version, Instant at) {
    return jdbc.update(
        "INSERT INTO review_rating (review_id, product_id, rating, deleted, version, updated_at)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT (review_id) DO UPDATE SET"
            + " product_id = EXCLUDED.product_id, rating = EXCLUDED.rating, deleted = EXCLUDED.deleted,"
            + " version = EXCLUDED.version, updated_at = EXCLUDED.updated_at"
            + " WHERE review_rating.version < EXCLUDED.version",
        reviewId,
        productId,
        rating,
        deleted,
        version,
        Timestamp.from(at));
  }

  /** 센터 취급 사본(BR-49, V4) — version 이 더 큰 사건만 반영(재발행·늦게 온 사건은 무시). */
  public void upsertHandling(
      String mfcId, String sku, boolean enabled, int version, Instant changedAt) {
    jdbc.update(
        "INSERT INTO sku_handling (mfc_id, sku, handling_enabled, version, changed_at) VALUES (?,?,?,?,?)"
            + " ON CONFLICT (mfc_id, sku) DO UPDATE SET handling_enabled = EXCLUDED.handling_enabled,"
            + " version = EXCLUDED.version, changed_at = EXCLUDED.changed_at"
            + " WHERE sku_handling.version < EXCLUDED.version",
        mfcId,
        sku,
        enabled,
        version,
        Timestamp.from(changedAt));
  }

  /** mfcId 가 있으면 그 센터가 대표 SKU 를 취급하는 상품만(BR-49 — 레코드 없음 = 취급 안 함). */
  private static String carriedAt(@Nullable String mfcId) {
    return mfcId == null
        ? ""
        : " AND EXISTS (SELECT 1 FROM sku_handling h WHERE h.mfc_id = ? AND h.sku = p.representative_sku"
            + " AND h.handling_enabled)";
  }

  /** 진열 가능한 상품(게시 콘텐츠·상품 버전 있음, 판매중지 아님) 최신 게시 순. */
  public List<Item> latest(int offset, int limit) {
    return latest(null, offset, limit);
  }

  /** mfcId 가 있으면 그 센터 미취급 상품은 뺀다(BR-49). */
  public List<Item> latest(@Nullable String mfcId, int offset, int limit) {
    List<Object> args = new java.util.ArrayList<>();
    if (mfcId != null) {
      args.add(mfcId);
    }
    args.add(offset);
    args.add(limit);
    return jdbc.query(
        "SELECT d.*, r.n AS review_count, r.avg_rating FROM (SELECT p.product_id, p.name,"
            + " p.image_url, p.representative_sku, s.price, s.price_version,"
            + " COALESCE(p.sale_state, 'ON_SALE') AS sale_state, p.net_content_amount,"
            + " p.net_content_unit, p.content_published_at"
            + DISPLAYABLE
            + carriedAt(mfcId)
            + " ORDER BY p.content_published_at DESC NULLS LAST, p.product_id OFFSET ? LIMIT ?) d"
            // 평점(BR-63) — 고른 쪽의 상품만 색인으로 센다
            + " LEFT JOIN LATERAL (SELECT count(*) AS n, avg(rating)::float8 AS avg_rating FROM review_rating x"
            + " WHERE x.product_id = d.product_id AND NOT x.deleted) r ON true"
            + " ORDER BY d.content_published_at DESC NULLS LAST, d.product_id",
        (rs, i) ->
            new Item(
                rs.getString("product_id"),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getString("representative_sku"),
                rs.getObject("price", Long.class),
                rs.getObject("price_version", Integer.class),
                rs.getString("sale_state"),
                rs.getObject("net_content_amount", Integer.class),
                rs.getString("net_content_unit"),
                rs.getInt("review_count"),
                rs.getObject("avg_rating", Double.class)),
        args.toArray());
  }

  public int countDisplayable() {
    return countDisplayable(null);
  }

  public int countDisplayable(@Nullable String mfcId) {
    Integer n =
        mfcId == null
            ? jdbc.queryForObject("SELECT COUNT(*)" + DISPLAYABLE, Integer.class)
            : jdbc.queryForObject(
                "SELECT COUNT(*)" + DISPLAYABLE + carriedAt(mfcId), Integer.class, mfcId);
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
