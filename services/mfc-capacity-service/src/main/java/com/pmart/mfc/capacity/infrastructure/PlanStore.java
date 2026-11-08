package com.pmart.mfc.capacity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 배정 추천(V6): 머리·항목·미배정. */
@Repository
public class PlanStore {

  public record PlanRow(
      String planId,
      String mfcId,
      String state,
      String solverStatus,
      Instant horizonStart,
      int horizonMinutes,
      int unassignedCount,
      int lateMinutes,
      int maxLoadMinutes,
      long objectiveValue,
      int wallTimeMs,
      List<String> staleReasons,
      String createdBy,
      Instant createdAt,
      @Nullable String committedBy,
      @Nullable Instant committedAt,
      @Nullable String settings,
      List<String> unverifiedWorkers) {}

  public record ItemRow(
      String taskId,
      String taskType,
      int taskVersion,
      String workerId,
      int dutyVersion,
      Instant startAt,
      Instant endAt,
      int lateMinutes) {}

  public record UnassignedRow(String taskId, String taskType, String reason) {}

  private final JdbcTemplate jdbc;

  public PlanStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PlanRow p, List<ItemRow> items, List<UnassignedRow> left) {
    jdbc.update(
        "INSERT INTO assignment_plan (plan_id, mfc_id, state, solver_status, horizon_start,"
            + " horizon_minutes, unassigned_count, late_minutes, max_load_minutes, objective_value,"
            + " wall_time_ms, created_by, created_at, settings, unverified_workers)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        p.planId(),
        p.mfcId(),
        p.state(),
        p.solverStatus(),
        Timestamp.from(p.horizonStart()),
        p.horizonMinutes(),
        p.unassignedCount(),
        p.lateMinutes(),
        p.maxLoadMinutes(),
        p.objectiveValue(),
        p.wallTimeMs(),
        p.createdBy(),
        Timestamp.from(p.createdAt()),
        p.settings(),
        String.join("\n", p.unverifiedWorkers()));
    for (ItemRow i : items) {
      jdbc.update(
          "INSERT INTO assignment_plan_item (plan_id, task_id, task_type, task_version, worker_id,"
              + " duty_version, start_at, end_at, late_minutes) VALUES (?,?,?,?,?,?,?,?,?)",
          p.planId(),
          i.taskId(),
          i.taskType(),
          i.taskVersion(),
          i.workerId(),
          i.dutyVersion(),
          Timestamp.from(i.startAt()),
          Timestamp.from(i.endAt()),
          i.lateMinutes());
    }
    for (UnassignedRow u : left) {
      jdbc.update(
          "INSERT INTO assignment_plan_unassigned (plan_id, task_id, task_type, reason)"
              + " VALUES (?,?,?,?)",
          p.planId(),
          u.taskId(),
          u.taskType(),
          u.reason());
    }
  }

  public Optional<PlanRow> find(String planId) {
    return jdbc
        .query("SELECT * FROM assignment_plan WHERE plan_id = ?", (rs, i) -> plan(rs), planId)
        .stream()
        .findFirst();
  }

  public Optional<PlanRow> lock(String planId) {
    return jdbc
        .query(
            "SELECT * FROM assignment_plan WHERE plan_id = ? FOR UPDATE",
            (rs, i) -> plan(rs),
            planId)
        .stream()
        .findFirst();
  }

  public List<ItemRow> items(String planId) {
    return jdbc.query(
        "SELECT * FROM assignment_plan_item WHERE plan_id = ? ORDER BY start_at, task_id",
        (rs, i) ->
            new ItemRow(
                rs.getString("task_id"),
                rs.getString("task_type"),
                rs.getInt("task_version"),
                rs.getString("worker_id"),
                rs.getInt("duty_version"),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getInt("late_minutes")),
        planId);
  }

  public List<UnassignedRow> unassigned(String planId) {
    return jdbc.query(
        "SELECT * FROM assignment_plan_unassigned WHERE plan_id = ? ORDER BY task_id",
        (rs, i) ->
            new UnassignedRow(
                rs.getString("task_id"), rs.getString("task_type"), rs.getString("reason")),
        planId);
  }

  public void markStale(String planId, List<String> reasons) {
    jdbc.update(
        "UPDATE assignment_plan SET state = 'STALE', stale_reasons = ? WHERE plan_id = ?",
        String.join("\n", reasons),
        planId);
  }

  public void markCommitted(String planId, String by, Instant at) {
    jdbc.update(
        "UPDATE assignment_plan SET state = 'COMMITTED', committed_by = ?, committed_at = ?"
            + " WHERE plan_id = ?",
        by,
        Timestamp.from(at),
        planId);
  }

  private static PlanRow plan(ResultSet rs) throws SQLException {
    String stale = rs.getString("stale_reasons");
    String uw = rs.getString("unverified_workers");
    Timestamp ca = rs.getTimestamp("committed_at");
    return new PlanRow(
        rs.getString("plan_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("solver_status"),
        rs.getTimestamp("horizon_start").toInstant(),
        rs.getInt("horizon_minutes"),
        rs.getInt("unassigned_count"),
        rs.getInt("late_minutes"),
        rs.getInt("max_load_minutes"),
        rs.getLong("objective_value"),
        rs.getInt("wall_time_ms"),
        stale == null || stale.isEmpty() ? List.of() : Arrays.asList(stale.split("\n")),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("committed_by"),
        ca == null ? null : ca.toInstant(),
        rs.getString("settings"),
        uw == null || uw.isEmpty() ? List.of() : Arrays.asList(uw.split("\n")));
  }
}
