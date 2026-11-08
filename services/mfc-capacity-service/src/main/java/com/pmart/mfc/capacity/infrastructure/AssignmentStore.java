package com.pmart.mfc.capacity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업 배정 저장(JDBC). 작업마다 활성 1건(부분 유일 인덱스). */
@Repository
public class AssignmentStore {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record AssignmentRow(
      String assignmentId,
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      int assignmentVersion,
      String state,
      @Nullable String planId,
      @Nullable String assignedBy,
      @Nullable String reason,
      Instant assignedAt,
      @Nullable String temperatureZone) {}

  private final JdbcTemplate jdbc;

  public AssignmentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 작업의 활성 배정을 잠근다(없으면 empty). 같은 작업의 동시 배정은 이 잠금·유일 인덱스로 하나만. */
  public Optional<AssignmentRow> lockActive(String taskId) {
    return jdbc
        .query(
            "SELECT * FROM task_assignment WHERE task_id = ? AND state = 'ACTIVE' FOR UPDATE",
            (rs, i) -> row(rs),
            taskId)
        .stream()
        .findFirst();
  }

  public int maxVersion(String taskId) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(assignment_version), 0) FROM task_assignment WHERE task_id = ?",
            Integer.class,
            taskId);
    return v == null ? 0 : v;
  }

  public void supersede(String assignmentId, Instant at) {
    jdbc.update(
        "UPDATE task_assignment SET state = 'SUPERSEDED', ended_at = ? WHERE assignment_id = ?",
        Timestamp.from(at),
        assignmentId);
  }

  public void insert(AssignmentRow r) {
    jdbc.update(
        "INSERT INTO task_assignment (assignment_id, mfc_id, task_id, task_type, worker_id,"
            + " assignment_version, state, plan_id, assigned_by, reason, assigned_at, temperature_zone)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.assignmentId(),
        r.mfcId(),
        r.taskId(),
        r.taskType(),
        r.workerId(),
        r.assignmentVersion(),
        r.state(),
        r.planId(),
        r.assignedBy(),
        r.reason(),
        Timestamp.from(r.assignedAt()),
        r.temperatureZone());
  }

  public List<AssignmentRow> list(
      String mfcId,
      @Nullable String taskId,
      @Nullable String workerId,
      String state,
      @Nullable LocalDate date) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM task_assignment WHERE mfc_id = ? AND state = ?");
    List<Object> args = new ArrayList<>(List.of(mfcId, state));
    if (taskId != null) {
      sql.append(" AND task_id = ?");
      args.add(taskId);
    }
    if (workerId != null) {
      sql.append(" AND worker_id = ?");
      args.add(workerId);
    }
    if (date != null) {
      sql.append(" AND assigned_at >= ? AND assigned_at < ?");
      args.add(Timestamp.from(date.atStartOfDay(SEOUL).toInstant()));
      args.add(Timestamp.from(date.plusDays(1).atStartOfDay(SEOUL).toInstant()));
    }
    sql.append(" ORDER BY assigned_at, task_id LIMIT 500");
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  private static AssignmentRow row(ResultSet rs) throws SQLException {
    return new AssignmentRow(
        rs.getString("assignment_id"),
        rs.getString("mfc_id"),
        rs.getString("task_id"),
        rs.getString("task_type"),
        rs.getString("worker_id"),
        rs.getInt("assignment_version"),
        rs.getString("state"),
        rs.getString("plan_id"),
        rs.getString("assigned_by"),
        rs.getString("reason"),
        rs.getTimestamp("assigned_at").toInstant(),
        rs.getString("temperature_zone"));
  }
}
