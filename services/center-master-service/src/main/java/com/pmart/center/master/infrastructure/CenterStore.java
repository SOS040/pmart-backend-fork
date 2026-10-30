package com.pmart.center.master.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터·시간표 저장(JDBC). JSON 열은 문자열로 주고받는다. */
@Repository
public class CenterStore {

  public record CenterRow(
      String centerId,
      int version,
      String type,
      String name,
      String state,
      @Nullable String address,
      @Nullable Double lat,
      @Nullable Double lng,
      String temperatureZones,
      String profile,
      String approvalRef,
      @Nullable String approvedBy,
      Instant effectiveFrom,
      Instant updatedAt) {}

  public record TimetableRow(
      String centerId,
      String kind,
      int version,
      String weekly,
      String holidays,
      String restrictions,
      String approvalRef,
      @Nullable String approvedBy,
      Instant effectiveFrom) {}

  private final JdbcTemplate jdbc;

  public CenterStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<CenterRow> center(String centerId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT center_id, version, type, name, state, address, lat, lng,"
                + " temperature_zones::text AS tz, profile::text AS profile, approval_ref, approved_by,"
                + " effective_from, updated_at FROM center WHERE center_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> centerRow(rs),
            centerId)
        .stream()
        .findFirst();
  }

  public List<CenterRow> centers(
      @Nullable String type, @Nullable String state, @Nullable String zone, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT center_id, version, type, name, state, address, lat, lng,"
                + " temperature_zones::text AS tz, profile::text AS profile, approval_ref, approved_by,"
                + " effective_from, updated_at FROM center WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (zone != null) {
      sql.append(" AND jsonb_exists(temperature_zones, ?)");
      args.add(zone);
    }
    sql.append(" ORDER BY center_id OFFSET ? LIMIT ?");
    args.add(offset);
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> centerRow(rs), args.toArray());
  }

  public void upsertCenter(CenterRow c) {
    jdbc.update(
        "INSERT INTO center (center_id, version, type, name, state, address, lat, lng, temperature_zones,"
            + " profile, approval_ref, approved_by, effective_from, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)"
            + " ON CONFLICT (center_id) DO UPDATE SET version = EXCLUDED.version, type = EXCLUDED.type,"
            + " name = EXCLUDED.name, state = EXCLUDED.state, address = EXCLUDED.address,"
            + " lat = EXCLUDED.lat, lng = EXCLUDED.lng, temperature_zones = EXCLUDED.temperature_zones,"
            + " profile = EXCLUDED.profile, approval_ref = EXCLUDED.approval_ref,"
            + " approved_by = EXCLUDED.approved_by, effective_from = EXCLUDED.effective_from,"
            + " updated_at = EXCLUDED.updated_at",
        c.centerId(),
        c.version(),
        c.type(),
        c.name(),
        c.state(),
        c.address(),
        c.lat(),
        c.lng(),
        c.temperatureZones(),
        c.profile(),
        c.approvalRef(),
        c.approvedBy(),
        ts(c.effectiveFrom()),
        ts(c.updatedAt()));
  }

  public Optional<TimetableRow> timetable(String centerId, String kind) {
    return jdbc
        .query(
            "SELECT center_id, kind, version, weekly::text AS weekly, holidays::text AS holidays,"
                + " restrictions::text AS restrictions, approval_ref, approved_by, effective_from"
                + " FROM center_timetable WHERE center_id = ? AND kind = ?",
            (rs, i) ->
                new TimetableRow(
                    rs.getString("center_id"),
                    rs.getString("kind"),
                    rs.getInt("version"),
                    rs.getString("weekly"),
                    rs.getString("holidays"),
                    rs.getString("restrictions"),
                    rs.getString("approval_ref"),
                    rs.getString("approved_by"),
                    rs.getTimestamp("effective_from").toInstant()),
            centerId,
            kind)
        .stream()
        .findFirst();
  }

  public void upsertTimetable(TimetableRow t) {
    jdbc.update(
        "INSERT INTO center_timetable (center_id, kind, version, weekly, holidays, restrictions,"
            + " approval_ref, approved_by, effective_from) VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)"
            + " ON CONFLICT (center_id, kind) DO UPDATE SET version = EXCLUDED.version,"
            + " weekly = EXCLUDED.weekly, holidays = EXCLUDED.holidays,"
            + " restrictions = EXCLUDED.restrictions, approval_ref = EXCLUDED.approval_ref,"
            + " approved_by = EXCLUDED.approved_by, effective_from = EXCLUDED.effective_from",
        t.centerId(),
        t.kind(),
        t.version(),
        t.weekly(),
        t.holidays(),
        t.restrictions(),
        t.approvalRef(),
        t.approvedBy(),
        ts(t.effectiveFrom()));
  }

  public boolean applied(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_application WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n != null && n > 0;
  }

  public void recordApplication(String approvalId, String result, Instant at) {
    jdbc.update(
        "INSERT INTO approval_application (approval_id, result, applied_at) VALUES (?,?,?)",
        approvalId,
        result.length() > 200 ? result.substring(0, 200) : result,
        ts(at));
  }

  private static CenterRow centerRow(ResultSet rs) throws SQLException {
    return new CenterRow(
        rs.getString("center_id"),
        rs.getInt("version"),
        rs.getString("type"),
        rs.getString("name"),
        rs.getString("state"),
        rs.getString("address"),
        rs.getObject("lat", Double.class),
        rs.getObject("lng", Double.class),
        rs.getString("tz"),
        rs.getString("profile"),
        rs.getString("approval_ref"),
        rs.getString("approved_by"),
        rs.getTimestamp("effective_from").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
