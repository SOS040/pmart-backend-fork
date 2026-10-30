package com.pmart.delivery.serviceability.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 매핑 버전·권역·판정 결과 저장(JDBC). 권역 경계는 GeoJSON 원문(jsonb)으로 둔다. */
@Repository
public class MappingStore {

  public record VersionRow(
      int version,
      String state,
      String draftHash,
      @Nullable String approvalId,
      @Nullable Instant approvedAt,
      @Nullable Instant effectiveFrom,
      @Nullable Instant supersededAt,
      @Nullable String changeSummary,
      String testAddresses,
      int testVerified,
      Instant createdAt) {}

  public record ZoneRow(
      int version,
      String zoneId,
      String mfcId,
      @Nullable String name,
      @Nullable Integer priority,
      String geometry) {}

  public record CheckRow(
      String checkId,
      String result,
      @Nullable String mfcId,
      @Nullable String zoneId,
      @Nullable Integer mappingVersion,
      String overlapping,
      String missingFields,
      @Nullable Double lat,
      @Nullable Double lng,
      Instant evaluatedAt) {}

  private final JdbcTemplate jdbc;

  public MappingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int nextVersion() {
    Integer v = jdbc.queryForObject("SELECT nextval('mapping_version_seq')::int", Integer.class);
    return v == null ? 1 : v;
  }

  public void insertVersion(
      int version,
      String draftHash,
      @Nullable Instant effectiveFrom,
      @Nullable String summary,
      String testAddresses,
      String createdBy,
      Instant at) {
    jdbc.update(
        "INSERT INTO mapping_version (version, state, draft_hash, effective_from, change_summary,"
            + " test_addresses, test_verified, created_by, created_at) VALUES (?,'DRAFT',?,?,?,?::jsonb,0,?,?)",
        version,
        draftHash,
        ts(effectiveFrom),
        summary,
        testAddresses,
        createdBy,
        ts(at));
  }

  public void insertZone(ZoneRow z) {
    jdbc.update(
        "INSERT INTO mapping_zone (version, zone_id, mfc_id, name, priority, geometry)"
            + " VALUES (?,?,?,?,?,?::jsonb)",
        z.version(),
        z.zoneId(),
        z.mfcId(),
        z.name(),
        z.priority(),
        z.geometry());
  }

  public Optional<VersionRow> version(int version, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM mapping_version WHERE version = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> versionRow(rs),
            version)
        .stream()
        .findFirst();
  }

  public Optional<VersionRow> active() {
    return jdbc
        .query("SELECT * FROM mapping_version WHERE state = 'ACTIVE'", (rs, i) -> versionRow(rs))
        .stream()
        .findFirst();
  }

  public List<ZoneRow> zones(int version) {
    return jdbc.query(
        "SELECT version, zone_id, mfc_id, name, priority, geometry::text AS geometry"
            + " FROM mapping_zone WHERE version = ? ORDER BY zone_id",
        (rs, i) ->
            new ZoneRow(
                rs.getInt("version"),
                rs.getString("zone_id"),
                rs.getString("mfc_id"),
                rs.getString("name"),
                rs.getObject("priority", Integer.class),
                rs.getString("geometry")),
        version);
  }

  /** 발효: 이전 ACTIVE → SUPERSEDED, 이 버전 → ACTIVE. */
  public void activate(int version, String approvalId, int testVerified, Instant at) {
    jdbc.update(
        "UPDATE mapping_version SET state = 'SUPERSEDED', superseded_at = ? WHERE state = 'ACTIVE'",
        ts(at));
    jdbc.update(
        "UPDATE mapping_version SET state = 'ACTIVE', approval_id = ?, approved_at = ?,"
            + " effective_from = COALESCE(effective_from, ?), test_verified = ? WHERE version = ?",
        approvalId,
        ts(at),
        ts(at),
        testVerified,
        version);
  }

  public void reject(int version, @Nullable String approvalId, Instant at) {
    jdbc.update(
        "UPDATE mapping_version SET state = 'REJECTED', approval_id = ?, approved_at = ?"
            + " WHERE version = ? AND state = 'DRAFT'",
        approvalId,
        ts(at),
        version);
  }

  public boolean approvalApplied(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_application WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n != null && n > 0;
  }

  public void recordApplication(String approvalId, String targetRef, String result, Instant at) {
    jdbc.update(
        "INSERT INTO approval_application (approval_id, target_ref, result, applied_at) VALUES (?,?,?,?)",
        approvalId,
        targetRef,
        result,
        ts(at));
  }

  public void insertCheck(CheckRow c, @Nullable String purpose) {
    jdbc.update(
        "INSERT INTO serviceability_check (check_id, result, mfc_id, zone_id, mapping_version, overlapping,"
            + " missing_fields, lat, lng, purpose, evaluated_at) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)",
        c.checkId(),
        c.result(),
        c.mfcId(),
        c.zoneId(),
        c.mappingVersion(),
        c.overlapping(),
        c.missingFields(),
        c.lat(),
        c.lng(),
        purpose,
        ts(c.evaluatedAt()));
  }

  public Optional<CheckRow> check(String checkId) {
    return jdbc
        .query(
            "SELECT check_id, result, mfc_id, zone_id, mapping_version, overlapping::text AS overlapping,"
                + " missing_fields::text AS missing_fields, lat, lng, evaluated_at"
                + " FROM serviceability_check WHERE check_id = ?",
            (rs, i) ->
                new CheckRow(
                    rs.getString("check_id"),
                    rs.getString("result"),
                    rs.getString("mfc_id"),
                    rs.getString("zone_id"),
                    rs.getObject("mapping_version", Integer.class),
                    rs.getString("overlapping"),
                    rs.getString("missing_fields"),
                    rs.getObject("lat", Double.class),
                    rs.getObject("lng", Double.class),
                    rs.getTimestamp("evaluated_at").toInstant()),
            checkId)
        .stream()
        .findFirst();
  }

  private static VersionRow versionRow(ResultSet rs) throws SQLException {
    return new VersionRow(
        rs.getInt("version"),
        rs.getString("state"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        instant(rs, "approved_at"),
        instant(rs, "effective_from"),
        instant(rs, "superseded_at"),
        rs.getString("change_summary"),
        rs.getString("test_addresses"),
        rs.getInt("test_verified"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static @Nullable Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
