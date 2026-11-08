package com.pmart.mfc.capacity.infrastructure;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업자 자격·고용 형태·일일크루 그날 등록(작업자 쪽 2단계, V9). */
@Repository
public class QualificationStore {

  public record Qualification(
      int version,
      Set<String> taskTypes,
      @Nullable Instant expiresAt,
      @Nullable String reason,
      String changedBy,
      Instant changedAt) {
    public boolean valid(Instant now) {
      return expiresAt == null || now.isBefore(expiresAt);
    }
  }

  public record Day(
      String workerId,
      LocalDate businessDate,
      Set<String> dutyTaskTypes,
      String enrolledBy,
      Instant enrolledAt) {}

  private final JdbcTemplate jdbc;

  public QualificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String employmentType(String mfcId, String workerId) {
    List<String> r =
        jdbc.queryForList(
            "SELECT employment_type FROM mfc_worker WHERE mfc_id = ? AND worker_id = ?",
            String.class,
            mfcId,
            workerId);
    return r.isEmpty() ? "REGULAR" : r.get(0);
  }

  public void setEmploymentType(String mfcId, String workerId, String type) {
    jdbc.update(
        "UPDATE mfc_worker SET employment_type = ? WHERE mfc_id = ? AND worker_id = ?",
        type,
        mfcId,
        workerId);
  }

  public Optional<Qualification> current(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT q.* FROM mfc_worker_qualification q JOIN mfc_worker w ON w.mfc_id = q.mfc_id"
                + " AND w.worker_id = q.worker_id AND w.qualification_version = q.qualification_version"
                + " WHERE q.mfc_id = ? AND q.worker_id = ?",
            (rs, i) -> qualification(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  public void insert(
      String mfcId,
      String workerId,
      int version,
      Set<String> taskTypes,
      @Nullable Instant expiresAt,
      @Nullable String reason,
      String by,
      Instant at) {
    jdbc.update(
        "INSERT INTO mfc_worker_qualification (mfc_id, worker_id, qualification_version, task_types,"
            + " expires_at, reason, changed_by, changed_at) VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setString(1, mfcId);
          ps.setString(2, workerId);
          ps.setInt(3, version);
          ps.setArray(
              4, ps.getConnection().createArrayOf("text", new TreeSet<>(taskTypes).toArray()));
          ps.setTimestamp(5, expiresAt == null ? null : Timestamp.from(expiresAt));
          ps.setString(6, reason);
          ps.setString(7, by);
          ps.setTimestamp(8, Timestamp.from(at));
        });
    jdbc.update(
        "UPDATE mfc_worker SET qualification_version = ?, updated_at = ? WHERE mfc_id = ? AND worker_id = ?",
        version,
        Timestamp.from(at),
        mfcId,
        workerId);
  }

  public Optional<Day> day(String mfcId, String workerId, LocalDate date) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker_day WHERE mfc_id = ? AND worker_id = ? AND business_date = ?",
            (rs, i) ->
                new Day(
                    rs.getString("worker_id"),
                    rs.getDate("business_date").toLocalDate(),
                    set(rs.getArray("duty_task_types")),
                    rs.getString("enrolled_by"),
                    rs.getTimestamp("enrolled_at").toInstant()),
            mfcId,
            workerId,
            Date.valueOf(date))
        .stream()
        .findFirst();
  }

  /**
   * @return 새로 등록했으면 true(이미 있으면 false).
   */
  public boolean enroll(
      String mfcId, String workerId, LocalDate date, Set<String> duty, String by, Instant at) {
    return jdbc.update(
            "INSERT INTO mfc_worker_day (mfc_id, worker_id, business_date, duty_task_types, enrolled_by,"
                + " enrolled_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            ps -> {
              ps.setString(1, mfcId);
              ps.setString(2, workerId);
              ps.setDate(3, Date.valueOf(date));
              ps.setArray(
                  4, ps.getConnection().createArrayOf("text", new TreeSet<>(duty).toArray()));
              ps.setString(5, by);
              ps.setTimestamp(6, Timestamp.from(at));
            })
        == 1;
  }

  private static Qualification qualification(ResultSet rs) throws SQLException {
    Timestamp exp = rs.getTimestamp("expires_at");
    return new Qualification(
        rs.getInt("qualification_version"),
        set(rs.getArray("task_types")),
        exp == null ? null : exp.toInstant(),
        rs.getString("reason"),
        rs.getString("changed_by"),
        rs.getTimestamp("changed_at").toInstant());
  }

  private static Set<String> set(Array a) throws SQLException {
    return new TreeSet<>(List.of((String[]) a.getArray()));
  }
}
