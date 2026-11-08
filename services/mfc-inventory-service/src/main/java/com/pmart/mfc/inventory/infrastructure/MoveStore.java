package com.pmart.mfc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 재고 이동 배정(V13). 진행 중 = ACTIVE·CANCEL_PENDING. */
@Repository
public class MoveStore {

  public record MoveRow(
      String moveId,
      String mfcId,
      String sku,
      String lotId,
      String fromLocation,
      String toLocation,
      @Nullable String cartLocation,
      int qty,
      int pickedQty,
      int droppedQty,
      String state,
      @Nullable String sizeClass,
      @Nullable Integer destinationCapacity,
      String contentHash,
      Instant createdAt,
      Instant updatedAt) {
    public int onCart() {
      return pickedQty - droppedQty;
    }

    /** 아직 집지 않은 배정(출발 칸 보류). */
    public int unpicked() {
      return qty - pickedQty;
    }

    /** 도착 칸에 들어올 예정(배정 − 내려놓음). */
    public int incoming() {
      return qty - droppedQty;
    }

    public boolean open() {
      return "ACTIVE".equals(state) || "CANCEL_PENDING".equals(state);
    }
  }

  private final JdbcTemplate jdbc;

  public MoveStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insert(MoveRow m) {
    return jdbc.update(
            "INSERT INTO move_allocation (move_id, mfc_id, sku, lot_id, from_location, to_location,"
                + " qty, state, size_class, destination_capacity, content_hash, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?) ON CONFLICT DO NOTHING",
            m.moveId(),
            m.mfcId(),
            m.sku(),
            m.lotId(),
            m.fromLocation(),
            m.toLocation(),
            m.qty(),
            m.sizeClass(),
            m.destinationCapacity(),
            m.contentHash(),
            Timestamp.from(m.createdAt()),
            Timestamp.from(m.updatedAt()))
        == 1;
  }

  public Optional<MoveRow> find(String moveId) {
    return jdbc
        .query("SELECT * FROM move_allocation WHERE move_id = ?", (rs, i) -> row(rs), moveId)
        .stream()
        .findFirst();
  }

  public Optional<MoveRow> lock(String moveId) {
    return jdbc
        .query(
            "SELECT * FROM move_allocation WHERE move_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            moveId)
        .stream()
        .findFirst();
  }

  /** 이 칸을 출발 또는 도착으로 쓰는 진행 중 이동. */
  public List<MoveRow> openAt(String mfcId, String location) {
    return jdbc.query(
        "SELECT * FROM move_allocation WHERE mfc_id = ? AND state IN ('ACTIVE', 'CANCEL_PENDING')"
            + " AND (from_location = ? OR to_location = ?)",
        (rs, i) -> row(rs),
        mfcId,
        location,
        location);
  }

  /** 이 상품의 진행 중 이동. */
  public List<MoveRow> openForSku(String mfcId, String sku) {
    return jdbc.query(
        "SELECT * FROM move_allocation WHERE mfc_id = ? AND sku = ?"
            + " AND state IN ('ACTIVE', 'CANCEL_PENDING')",
        (rs, i) -> row(rs),
        mfcId,
        sku);
  }

  public void update(
      String moveId,
      int qty,
      int picked,
      int dropped,
      @Nullable String cart,
      String state,
      Instant at) {
    jdbc.update(
        "UPDATE move_allocation SET qty = ?, picked_qty = ?, dropped_qty = ?, cart_location = ?,"
            + " state = ?, updated_at = ? WHERE move_id = ?",
        qty,
        picked,
        dropped,
        cart,
        state,
        Timestamp.from(at),
        moveId);
  }

  private static MoveRow row(ResultSet rs) throws SQLException {
    return new MoveRow(
        rs.getString("move_id"),
        rs.getString("mfc_id"),
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getString("from_location"),
        rs.getString("to_location"),
        rs.getString("cart_location"),
        rs.getInt("qty"),
        rs.getInt("picked_qty"),
        rs.getInt("dropped_qty"),
        rs.getString("state"),
        rs.getString("size_class"),
        (Integer) rs.getObject("destination_capacity"),
        rs.getString("content_hash"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
