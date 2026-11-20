package com.pmart.customer.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 찜 표 {@code customer_wishlist_item}(V16, ADR-0081). 계정·상품마다 한 줄. */
@Repository
public class WishlistStore {

  public record Item(String productId, Instant addedAt) {}

  private final JdbcTemplate jdbc;

  public WishlistStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Item> find(String accountId, String productId) {
    return jdbc
        .query(
            "SELECT product_id, added_at FROM customer_wishlist_item WHERE account_id = ? AND product_id = ?",
            (rs, i) -> new Item(rs.getString(1), rs.getTimestamp(2).toInstant()),
            accountId,
            productId)
        .stream()
        .findFirst();
  }

  public int count(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_wishlist_item WHERE account_id = ?",
            Integer.class,
            accountId);
    return n == null ? 0 : n;
  }

  /** 새로 넣었으면 true(이미 있으면 그대로 false). */
  public boolean insert(String accountId, String productId, Instant now) {
    return jdbc.update(
            "INSERT INTO customer_wishlist_item (account_id, product_id, added_at) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING",
            accountId,
            productId,
            Timestamp.from(now))
        == 1;
  }

  public void delete(String accountId, String productId) {
    jdbc.update(
        "DELETE FROM customer_wishlist_item WHERE account_id = ? AND product_id = ?",
        accountId,
        productId);
  }

  public void deleteAll(String accountId) {
    jdbc.update("DELETE FROM customer_wishlist_item WHERE account_id = ?", accountId);
  }

  public List<Item> page(String accountId, int offset, int limit) {
    return jdbc.query(
        "SELECT product_id, added_at FROM customer_wishlist_item WHERE account_id = ?"
            + " ORDER BY added_at DESC, product_id LIMIT ? OFFSET ?",
        (rs, i) -> new Item(rs.getString(1), rs.getTimestamp(2).toInstant()),
        accountId,
        limit,
        offset);
  }
}
