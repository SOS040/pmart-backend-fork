package com.pmart.cdc.outbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DC 백오피스 직원 조회(0.11.0, 2026-11-25) — 읽기만. */
@Repository
public class StaffViewStore {

  public record TransferRow(
      String trfId,
      int trfVersion,
      String mfcId,
      String state,
      String deadlineMeaning,
      Instant dueAt,
      @Nullable String waveId,
      @Nullable String allocationError,
      int lineCount,
      long approvedQty,
      long allocatedQty,
      long pickedQty,
      long shortQty,
      Instant createdAt,
      Instant updatedAt) {}

  public record LineRow(
      String sku, int approvedQty, int allocatedQty, int pickedQty, int shortQty) {}

  public record Totals(long count, long approved, long allocated, long picked, long shortQty) {}

  private static final String SUMMARY =
      "SELECT t.trf_id, t.trf_version, t.mfc_id, t.state, t.deadline_meaning, t.due_at, t.wave_id,"
          + " t.allocation_error, t.created_at, t.updated_at, count(l.sku) AS line_count,"
          + " coalesce(sum(l.approved_qty),0) AS approved, coalesce(sum(l.allocated_qty),0) AS allocated,"
          + " coalesce(sum(l.picked_qty),0) AS picked, coalesce(sum(l.short_qty),0) AS short_qty"
          + " FROM transfer t LEFT JOIN transfer_line l ON l.trf_id = t.trf_id";

  private final JdbcTemplate jdbc;

  public StaffViewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** size + 1 개를 읽어 다음 쪽 여부를 판정한다. */
  public List<TransferRow> transfers(
      String cdcId, @Nullable String state, @Nullable String mfcId, int page, int size) {
    StringBuilder sql = new StringBuilder(SUMMARY).append(" WHERE t.cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (state != null) {
      sql.append(" AND t.state = ?");
      args.add(state);
    }
    if (mfcId != null) {
      sql.append(" AND t.mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(" GROUP BY t.trf_id ORDER BY t.due_at, t.trf_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.query(sql.toString(), StaffViewStore::transfer, args.toArray());
  }

  /** Wave ID 목록(만든 시각 최신 순) — size + 1 개를 읽어 다음 쪽 여부를 판정한다(0.15.0 W-1). */
  public List<String> waveIds(String cdcId, @Nullable String state, int page, int size) {
    StringBuilder sql = new StringBuilder("SELECT wave_id FROM wave WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY created_at DESC, wave_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  /** 이 센터 TRF 가 든 출발 ID(검증 최신 순) — size + 1 개(0.16.0 M-3). */
  public List<String> departureIds(String cdcId, @Nullable String result, int page, int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.departure_id FROM departure d WHERE EXISTS (SELECT 1 FROM departure_transfer dt"
                + " JOIN transfer t ON t.trf_id = dt.trf_id WHERE dt.departure_id = d.departure_id"
                + " AND t.cdc_id = ?)");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (result != null) {
      sql.append(" AND d.overall_result = ?");
      args.add(result);
    }
    sql.append(" ORDER BY d.validated_at DESC, d.departure_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  public Optional<TransferRow> transfer(String cdcId, String trfId) {
    return jdbc
        .query(
            SUMMARY + " WHERE t.cdc_id = ? AND t.trf_id = ? GROUP BY t.trf_id",
            StaffViewStore::transfer,
            cdcId,
            trfId)
        .stream()
        .findFirst();
  }

  public List<LineRow> lines(String trfId) {
    return jdbc.query(
        "SELECT sku, approved_qty, allocated_qty, picked_qty, short_qty FROM transfer_line"
            + " WHERE trf_id = ? ORDER BY line_no",
        (rs, i) ->
            new LineRow(
                rs.getString("sku"),
                rs.getInt("approved_qty"),
                rs.getInt("allocated_qty"),
                rs.getInt("picked_qty"),
                rs.getInt("short_qty")),
        trfId);
  }

  /** [from, to) 기한의 TRF 상태별 수. */
  public Map<String, Long> transfersByState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT state AS k, count(*) AS n FROM transfer WHERE cdc_id = ? AND due_at >= ?"
            + " AND due_at < ? GROUP BY state ORDER BY state",
        cdcId,
        from,
        to);
  }

  public Totals totals(String cdcId, Instant from, Instant to) {
    return jdbc.queryForObject(
        "SELECT count(DISTINCT t.trf_id) AS c, coalesce(sum(l.approved_qty),0) AS a,"
            + " coalesce(sum(l.allocated_qty),0) AS al, coalesce(sum(l.picked_qty),0) AS p,"
            + " coalesce(sum(l.short_qty),0) AS s FROM transfer t"
            + " LEFT JOIN transfer_line l ON l.trf_id = t.trf_id"
            + " WHERE t.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?",
        (rs, i) ->
            new Totals(
                rs.getLong("c"),
                rs.getLong("a"),
                rs.getLong("al"),
                rs.getLong("p"),
                rs.getLong("s")),
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public record MfcTotals(
      String mfcId, long transfers, long approved, long allocated, long picked, long shortQty) {}

  /** [from, to) 기한 TRF 의 MFC 별 합(0.18.0 M-4) — MFC 순. */
  public List<MfcTotals> byMfc(String cdcId, Instant from, Instant to) {
    return jdbc.query(
        "SELECT t.mfc_id, count(DISTINCT t.trf_id) AS c, coalesce(sum(l.approved_qty),0) AS a,"
            + " coalesce(sum(l.allocated_qty),0) AS al, coalesce(sum(l.picked_qty),0) AS p,"
            + " coalesce(sum(l.short_qty),0) AS s FROM transfer t LEFT JOIN transfer_line l"
            + " ON l.trf_id = t.trf_id WHERE t.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?"
            + " GROUP BY t.mfc_id ORDER BY t.mfc_id",
        (rs, i) ->
            new MfcTotals(
                rs.getString("mfc_id"),
                rs.getLong("c"),
                rs.getLong("a"),
                rs.getLong("al"),
                rs.getLong("p"),
                rs.getLong("s")),
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /** 그 TRF 들이 든 Wave 의 상태별 수. */
  public Map<String, Long> wavesByState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT w.state AS k, count(*) AS n FROM wave w WHERE w.cdc_id = ? AND EXISTS ("
            + " SELECT 1 FROM transfer t WHERE t.wave_id = w.wave_id AND t.due_at >= ?"
            + " AND t.due_at < ?) GROUP BY w.state ORDER BY w.state",
        cdcId,
        from,
        to);
  }

  /** 그 TRF 들의 작업 종류:상태별 수. */
  public Map<String, Long> tasksByKindState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT k.kind || ':' || k.state AS k, count(*) AS n FROM task k JOIN transfer t"
            + " ON t.trf_id = k.trf_id WHERE k.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?"
            + " GROUP BY k.kind, k.state ORDER BY k.kind, k.state",
        cdcId,
        from,
        to);
  }

  private Map<String, Long> counts(String sql, String cdcId, Instant from, Instant to) {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.query(
        sql,
        rs -> {
          out.put(rs.getString("k"), rs.getLong("n"));
        },
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
    return out;
  }

  private static TransferRow transfer(ResultSet rs, int i) throws SQLException {
    return new TransferRow(
        rs.getString("trf_id"),
        rs.getInt("trf_version"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("deadline_meaning"),
        rs.getTimestamp("due_at").toInstant(),
        rs.getString("wave_id"),
        rs.getString("allocation_error"),
        rs.getInt("line_count"),
        rs.getLong("approved"),
        rs.getLong("allocated"),
        rs.getLong("picked"),
        rs.getLong("short_qty"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
