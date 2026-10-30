package com.pmart.cart.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 장바구니·품목·변경 결과·병합 결과 저장(JDBC). */
@Repository
public class CartStore {

  public record CartRow(
      String cartId,
      String ownerType,
      String ownerId,
      String state,
      @Nullable String deviceId,
      @Nullable String shoppingAddressId,
      @Nullable String mfcId,
      int version,
      Instant createdAt,
      Instant lastChangedAt) {}

  public record ItemRow(
      String cartItemId,
      String cartId,
      String sku,
      @Nullable String productId,
      int quantity,
      boolean selected,
      Instant addedAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public CartStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 장바구니 ----------

  public Optional<CartRow> activeCart(String ownerType, String ownerId) {
    return jdbc
        .query(
            "SELECT * FROM cart WHERE owner_type = ? AND owner_id = ? AND state = 'ACTIVE'",
            (rs, i) -> cartRow(rs),
            ownerType,
            ownerId)
        .stream()
        .findFirst();
  }

  public Optional<CartRow> cart(String cartId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM cart WHERE cart_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> cartRow(rs),
            cartId)
        .stream()
        .findFirst();
  }

  /** 진행 중 장바구니가 이미 있으면(동시 생성) 0. */
  public int insertCart(CartRow c) {
    return jdbc.update(
        "INSERT INTO cart (cart_id, owner_type, owner_id, state, device_id, shopping_address_id, mfc_id,"
            + " version, created_at, last_changed_at) VALUES (?,?,?,'ACTIVE',?,?,?,0,?,?)"
            + " ON CONFLICT (owner_type, owner_id) WHERE state = 'ACTIVE' DO NOTHING",
        c.cartId(),
        c.ownerType(),
        c.ownerId(),
        c.deviceId(),
        c.shoppingAddressId(),
        c.mfcId(),
        ts(c.createdAt()),
        ts(c.lastChangedAt()));
  }

  public void expire(String cartId) {
    jdbc.update("UPDATE cart SET state = 'EXPIRED' WHERE cart_id = ? AND state = 'ACTIVE'", cartId);
  }

  /** 쇼핑 주소·센터 기록(표시용). 버전은 올리지 않는다. */
  public void updateContext(
      String cartId, @Nullable String shoppingAddressId, @Nullable String mfcId) {
    jdbc.update(
        "UPDATE cart SET shopping_address_id = COALESCE(?, shopping_address_id),"
            + " mfc_id = COALESCE(?, mfc_id) WHERE cart_id = ?",
        shoppingAddressId,
        mfcId,
        cartId);
  }

  /** 변경 반영: 버전 +1, 마지막 변경 시각. */
  public int bump(String cartId, Instant now) {
    jdbc.update(
        "UPDATE cart SET version = version + 1, last_changed_at = ? WHERE cart_id = ?",
        ts(now),
        cartId);
    Integer v =
        jdbc.queryForObject("SELECT version FROM cart WHERE cart_id = ?", Integer.class, cartId);
    return v == null ? 0 : v;
  }

  // ---------- 품목 ----------

  public List<ItemRow> items(String cartId) {
    return jdbc.query(
        "SELECT * FROM cart_item WHERE cart_id = ? ORDER BY added_at, cart_item_id",
        (rs, i) -> itemRow(rs),
        cartId);
  }

  public Optional<ItemRow> item(String cartId, String cartItemId) {
    return jdbc
        .query(
            "SELECT * FROM cart_item WHERE cart_id = ? AND cart_item_id = ?",
            (rs, i) -> itemRow(rs),
            cartId,
            cartItemId)
        .stream()
        .findFirst();
  }

  public Optional<ItemRow> itemBySku(String cartId, String sku) {
    return jdbc
        .query(
            "SELECT * FROM cart_item WHERE cart_id = ? AND sku = ?",
            (rs, i) -> itemRow(rs),
            cartId,
            sku)
        .stream()
        .findFirst();
  }

  public void insertItem(ItemRow i) {
    jdbc.update(
        "INSERT INTO cart_item (cart_item_id, cart_id, sku, product_id, quantity, selected, added_at,"
            + " updated_at) VALUES (?,?,?,?,?,?,?,?)",
        i.cartItemId(),
        i.cartId(),
        i.sku(),
        i.productId(),
        i.quantity(),
        i.selected(),
        ts(i.addedAt()),
        ts(i.updatedAt()));
  }

  public void updateItem(String cartItemId, int quantity, boolean selected, Instant now) {
    jdbc.update(
        "UPDATE cart_item SET quantity = ?, selected = ?, updated_at = ? WHERE cart_item_id = ?",
        quantity,
        selected,
        ts(now),
        cartItemId);
  }

  public void deleteItem(String cartItemId) {
    jdbc.update("DELETE FROM cart_item WHERE cart_item_id = ?", cartItemId);
  }

  // ---------- 변경·병합 결과 ----------

  public void insertChange(String cartId, String requestId, String resultJson, Instant at) {
    jdbc.update(
        "INSERT INTO cart_change (cart_id, request_id, result, created_at) VALUES (?,?,?::jsonb,?)"
            + " ON CONFLICT DO NOTHING",
        cartId,
        requestId,
        resultJson,
        ts(at));
  }

  public Optional<String> change(String cartId, String requestId) {
    return jdbc
        .query(
            "SELECT result::text FROM cart_change WHERE cart_id = ? AND request_id = ?",
            (rs, i) -> rs.getString(1),
            cartId,
            requestId)
        .stream()
        .findFirst();
  }

  public void insertMerge(
      String mergeId,
      String targetCartId,
      String sourceCartId,
      String state,
      String resultJson,
      Instant at) {
    jdbc.update(
        "INSERT INTO cart_merge (merge_id, target_cart_id, source_cart_id, state, result, created_at)"
            + " VALUES (?,?,?,?,?::jsonb,?)",
        mergeId,
        targetCartId,
        sourceCartId,
        state,
        resultJson,
        ts(at));
  }

  public Optional<String> merge(String targetCartId, String mergeId) {
    return jdbc
        .query(
            "SELECT result::text FROM cart_merge WHERE target_cart_id = ? AND merge_id = ?",
            (rs, i) -> rs.getString(1),
            targetCartId,
            mergeId)
        .stream()
        .findFirst();
  }

  private static CartRow cartRow(ResultSet rs) throws SQLException {
    return new CartRow(
        rs.getString("cart_id"),
        rs.getString("owner_type"),
        rs.getString("owner_id"),
        rs.getString("state"),
        rs.getString("device_id"),
        rs.getString("shopping_address_id"),
        rs.getString("mfc_id"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("last_changed_at").toInstant());
  }

  private static ItemRow itemRow(ResultSet rs) throws SQLException {
    return new ItemRow(
        rs.getString("cart_item_id"),
        rs.getString("cart_id"),
        rs.getString("sku"),
        rs.getString("product_id"),
        rs.getInt("quantity"),
        rs.getBoolean("selected"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
