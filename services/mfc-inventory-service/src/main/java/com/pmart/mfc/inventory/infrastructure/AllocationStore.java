package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 예약의 위치 배정 {@code reservation_location_allocation}(V11). 남은 배정 = qty − picked_qty, ACTIVE 이고 조각이
 * CONFIRMED 일 때만 센다. 호출자가 예약 행 → 조각 → 센터 요약 → LOT 요약 순으로 잠근 뒤 쓴다.
 */
@Repository
public class AllocationStore {

  /** 배정 한 줄. remaining = qty − pickedQty. */
  public record Alloc(
      String allocationId,
      String partId,
      String locationCode,
      int qty,
      int pickedQty,
      String source,
      Instant createdAt) {
    public int remaining() {
      return qty - pickedQty;
    }
  }

  private final JdbcTemplate jdbc;

  public AllocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 조각의 ACTIVE 배정(만든 순서). */
  public List<Alloc> active(String partId) {
    return jdbc.query(
        "SELECT allocation_id, part_id, location_code, qty, picked_qty, source, created_at"
            + " FROM reservation_location_allocation WHERE part_id = ? AND state = 'ACTIVE'"
            + " ORDER BY seq",
        (rs, i) ->
            new Alloc(
                rs.getString("allocation_id"),
                rs.getString("part_id"),
                rs.getString("location_code"),
                rs.getInt("qty"),
                rs.getInt("picked_qty"),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant()),
        partId);
  }

  /** 이 LOT 의 위치별 남은 배정(확정 조각만). */
  public Map<String, Integer> outstandingByLocation(String mfcId, String lotId) {
    Map<String, Integer> out = new HashMap<>();
    jdbc.query(
        "SELECT a.location_code, SUM(a.qty - a.picked_qty) AS n"
            + " FROM reservation_location_allocation a JOIN reservation_part p ON p.part_id = a.part_id"
            + " WHERE a.mfc_id = ? AND a.lot_id = ? AND a.state = 'ACTIVE' AND p.state = 'CONFIRMED'"
            + " GROUP BY a.location_code",
        rs -> {
          out.put(rs.getString("location_code"), rs.getInt("n"));
        },
        mfcId,
        lotId);
    return out;
  }

  /** 키(lotId|locationCode) → 남은 배정. 스냅샷 표시용(확정 조각만). */
  public Map<String, Integer> outstanding(String mfcId, List<String> lotLocationKeys) {
    Map<String, Integer> out = new HashMap<>();
    if (lotLocationKeys.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT a.lot_id, a.location_code, SUM(a.qty - a.picked_qty) AS n"
            + " FROM reservation_location_allocation a JOIN reservation_part p ON p.part_id = a.part_id"
            + " WHERE a.mfc_id = ? AND a.state = 'ACTIVE' AND p.state = 'CONFIRMED'"
            + " AND (a.lot_id || '|' || a.location_code) = ANY (?)"
            + " GROUP BY a.lot_id, a.location_code",
        rs -> {
          out.put(rs.getString("lot_id") + "|" + rs.getString("location_code"), rs.getInt("n"));
        },
        mfcId,
        lotLocationKeys.toArray(String[]::new));
    return out;
  }

  public void insert(
      String partId,
      String reservationId,
      String mfcId,
      String sku,
      String lotId,
      String locationCode,
      int qty,
      int pickedQty,
      String source,
      Instant at) {
    jdbc.update(
        "INSERT INTO reservation_location_allocation (allocation_id, part_id, reservation_id, mfc_id,"
            + " sku, lot_id, location_code, qty, picked_qty, state, source, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)",
        "ALC-" + UUID.randomUUID(),
        partId,
        reservationId,
        mfcId,
        sku,
        lotId,
        locationCode,
        qty,
        pickedQty,
        source,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void addPicked(String allocationId, int qty, Instant at) {
    jdbc.update(
        "UPDATE reservation_location_allocation SET picked_qty = picked_qty + ?, updated_at = ?"
            + " WHERE allocation_id = ?",
        qty,
        Timestamp.from(at),
        allocationId);
  }

  /** 남은 배정을 줄인다. 남은 게 0 이고 집은 게 없으면 RELEASED. */
  public void shrink(Alloc a, int newRemaining, Instant at) {
    int qty = a.pickedQty() + newRemaining;
    jdbc.update(
        "UPDATE reservation_location_allocation SET qty = ?,"
            + " state = CASE WHEN ? = 0 THEN 'RELEASED' ELSE state END, updated_at = ?"
            + " WHERE allocation_id = ?",
        qty,
        qty,
        Timestamp.from(at),
        a.allocationId());
  }

  /** 이 칸들의 배정을 먼저 qty 만큼 깎는다(격리·실사 손실로 그 칸 물건이 없어졌을 때). */
  public void shrinkAt(String partId, java.util.Set<String> locations, int qty, Instant at) {
    int left = qty;
    for (Alloc a : active(partId)) {
      if (left == 0) {
        break;
      }
      if (locations.contains(a.locationCode()) && a.remaining() > 0) {
        int cut = Math.min(left, a.remaining());
        shrink(a, a.remaining() - cut, at);
        left -= cut;
      }
    }
  }

  /** 조각이 확정이 아니게 되면 그 조각의 배정을 놓는다. */
  public void releasePart(String partId, Instant at) {
    jdbc.update(
        "UPDATE reservation_location_allocation SET state = 'RELEASED', updated_at = ?"
            + " WHERE part_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        partId);
  }

  /** 예약의 조각 중 확정이 아닌 조각의 배정을 놓는다. */
  public void releaseUnconfirmed(String reservationId, Instant at) {
    jdbc.update(
        "UPDATE reservation_location_allocation a SET state = 'RELEASED', updated_at = ?"
            + " FROM reservation_part p WHERE p.part_id = a.part_id AND a.reservation_id = ?"
            + " AND a.state = 'ACTIVE' AND p.state <> 'CONFIRMED'",
        Timestamp.from(at),
        reservationId);
  }

  /**
   * 조각의 남은 배정 합이 조각의 남은 집품량(qty − picked)을 넘지 않게 나중 배정부터 줄인다. 조각 수량이 줄거나(결품·보류) 배정과 다른 칸에서 집었을 때.
   */
  public void trim(String partId, int partOutstanding, Instant at) {
    List<Alloc> all = active(partId);
    int sum = all.stream().mapToInt(Alloc::remaining).sum();
    int over = sum - Math.max(0, partOutstanding);
    for (int i = all.size() - 1; i >= 0 && over > 0; i--) {
      Alloc a = all.get(i);
      if (a.remaining() == 0) {
        continue;
      }
      int cut = Math.min(over, a.remaining());
      shrink(a, a.remaining() - cut, at);
      over -= cut;
    }
  }
}
