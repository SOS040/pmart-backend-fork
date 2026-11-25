package com.pmart.cdc.outbound.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 작업 조작(2026-11-26, 0.20.0 — DC 백오피스, 사용자 "큰 순서 전부 고치고"). 자기 센터 운영자만. 시작한 작업(IN_PROGRESS 이후)은 실적이
 * 섞이므로 재배정·보류하지 않는다.
 *
 * <ul>
 *   <li>재배정: CREATED·ACCEPTED → 작업자 지정이면 ACCEPTED(그 작업자가 다른 작업을 들고 있으면 409 WORKER_BUSY), 비우면
 *       CREATED.
 *   <li>보류: CREATED·ACCEPTED → HELD(담당 비움) — PDA 다음 작업 받기에서 빠진다. 재개: HELD → CREATED.
 *   <li>우선순위: 끝나지 않은 작업의 priority(-100~100) — 높을수록 다음 작업 받기에서 먼저.
 * </ul>
 *
 * 바뀔 때마다 {@code task_adjustment} 에 한 줄(누가·무엇을·왜). 같은 Idempotency-Key 는 같은 결과.
 */
@Service
public class TaskAdjustments {

  private static final Logger log = LoggerFactory.getLogger(TaskAdjustments.class);
  static final Set<String> NOT_STARTED = Set.of("CREATED", "ACCEPTED");
  static final Set<String> CLOSED = Set.of("DONE", "PARTIAL", "CANCELLED", "EXCEPTION");

  public record Result(String taskId, String state, int version) {}

  private final JdbcTemplate jdbc;
  private final RequestIdempotency idempotency;
  private final Support support;

  public TaskAdjustments(JdbcTemplate jdbc, RequestIdempotency idempotency, Support support) {
    this.jdbc = jdbc;
    this.idempotency = idempotency;
    this.support = support;
  }

  private record Row(String state, int version, @Nullable String assignee, int priority) {}

  public Result reassign(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion,
      @Nullable String workerId,
      @Nullable String reason) {
    String to = workerId == null || workerId.isBlank() ? null : workerId.strip();
    return run(
        requestId,
        sourceSystem,
        cdcId,
        taskId,
        expectedVersion,
        "REASSIGN",
        reason,
        Map.of("workerId", to == null ? "" : to),
        r -> {
          if (!NOT_STARTED.contains(r.state())) {
            throw invalid("시작 전 작업(CREATED·ACCEPTED)만 재배정할 수 있습니다(지금 " + r.state() + ").");
          }
          if (to != null) {
            Integer busy =
                jdbc.queryForObject(
                    "SELECT count(*) FROM task WHERE cdc_id = ? AND assignee_id = ? AND task_id <> ?"
                        + " AND state IN ('ACCEPTED','IN_PROGRESS')",
                    Integer.class,
                    cdcId,
                    to,
                    taskId);
            if (busy != null && busy > 0) {
              throw new PmartException(
                  "WORKER_BUSY",
                  org.springframework.http.HttpStatus.CONFLICT,
                  "그 작업자가 다른 작업을 들고 있습니다.",
                  false,
                  List.of());
            }
          }
          return new Row(to == null ? "CREATED" : "ACCEPTED", 0, to, r.priority());
        });
  }

  public Result hold(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion,
      @Nullable String reason) {
    return run(
        requestId,
        sourceSystem,
        cdcId,
        taskId,
        expectedVersion,
        "HOLD",
        reason,
        Map.of(),
        r -> {
          if (!NOT_STARTED.contains(r.state())) {
            throw invalid("시작 전 작업(CREATED·ACCEPTED)만 보류할 수 있습니다(지금 " + r.state() + ").");
          }
          return new Row("HELD", 0, null, r.priority());
        });
  }

  public Result resume(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion,
      @Nullable String reason) {
    return run(
        requestId,
        sourceSystem,
        cdcId,
        taskId,
        expectedVersion,
        "RESUME",
        reason,
        Map.of(),
        r -> {
          if (!"HELD".equals(r.state())) {
            throw invalid("보류(HELD) 작업만 재개할 수 있습니다(지금 " + r.state() + ").");
          }
          return new Row("CREATED", 0, null, r.priority());
        });
  }

  public Result priority(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion,
      int priority,
      @Nullable String reason) {
    if (priority < -100 || priority > 100) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "우선순위는 -100~100 입니다.");
    }
    return run(
        requestId,
        sourceSystem,
        cdcId,
        taskId,
        expectedVersion,
        "PRIORITY",
        reason,
        Map.of("priority", priority),
        r -> {
          if (CLOSED.contains(r.state())) {
            throw invalid("끝난 작업은 우선순위를 바꿀 수 없습니다(지금 " + r.state() + ").");
          }
          return new Row(r.state(), 0, r.assignee(), priority);
        });
  }

  private interface Change {
    Row apply(Row current);
  }

  private Result run(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion,
      String action,
      @Nullable String reason,
      Map<String, Object> body,
      Change change) {
    String actor = Support.requireCenter(cdcId);
    String why = reason == null || reason.isBlank() ? null : reason.strip();
    if (why != null && why.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유는 300자까지입니다.");
    }
    Map<String, Object> req = new LinkedHashMap<>(body);
    req.put("taskId", taskId);
    req.put("action", action);
    req.put("reason", why);
    req.put("expectedVersion", expectedVersion);
    return idempotency
        .execute(
            Support.key(sourceSystem, requestId, "adjustTask:" + action, actor),
            req,
            Result.class,
            () -> {
              Row cur =
                  jdbc
                      .query(
                          "SELECT state, row_version, assignee_id, priority FROM task WHERE task_id = ?"
                              + " AND cdc_id = ? FOR UPDATE",
                          (rs, i) ->
                              new Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4)),
                          taskId,
                          cdcId)
                      .stream()
                      .findFirst()
                      .orElseThrow(
                          () -> PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 이 없습니다."));
              if (expectedVersion != null && expectedVersion != cur.version()) {
                throw PmartException.versionConflict("작업이 그 사이 바뀌었습니다. 다시 조회하세요.");
              }
              Row next = change.apply(cur);
              Instant at = support.now();
              jdbc.update(
                  "UPDATE task SET state = ?, assignee_id = ?, priority = ?, row_version = row_version + 1,"
                      + " accepted_at = CASE WHEN ? = 'ACCEPTED' AND assignee_id IS DISTINCT FROM ?"
                      + " THEN ? ELSE accepted_at END WHERE task_id = ?",
                  next.state(),
                  next.assignee(),
                  next.priority(),
                  next.state(),
                  next.assignee(),
                  Timestamp.from(at),
                  taskId);
              jdbc.update(
                  "INSERT INTO task_adjustment (adjustment_id, task_id, cdc_id, action, from_state,"
                      + " to_state, from_assignee, to_assignee, from_priority, to_priority, reason,"
                      + " actor_id, adjusted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  Support.newId("TADJ"),
                  taskId,
                  cdcId,
                  action,
                  cur.state(),
                  next.state(),
                  cur.assignee(),
                  next.assignee(),
                  cur.priority(),
                  next.priority(),
                  why,
                  actor,
                  Timestamp.from(at));
              log.info(
                  "작업 조작 {} {} {}→{} 우선순위 {}→{}",
                  action,
                  taskId,
                  cur.state(),
                  next.state(),
                  cur.priority(),
                  next.priority());
              return new Result(taskId, next.state(), cur.version() + 1);
            })
        .value();
  }

  public record Adjustment(
      String adjustmentId,
      String action,
      String fromState,
      String toState,
      @Nullable String fromAssignee,
      @Nullable String toAssignee,
      int fromPriority,
      int toPriority,
      @Nullable String reason,
      String actorId,
      Instant at) {}

  /** 그 작업의 조작 이력(최신 순). */
  public List<Adjustment> history(String cdcId, String taskId) {
    Support.requireCenter(cdcId);
    return jdbc.query(
        "SELECT * FROM task_adjustment WHERE cdc_id = ? AND task_id = ? ORDER BY adjusted_at DESC",
        (rs, i) ->
            new Adjustment(
                rs.getString("adjustment_id"),
                rs.getString("action"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("from_assignee"),
                rs.getString("to_assignee"),
                rs.getInt("from_priority"),
                rs.getInt("to_priority"),
                rs.getString("reason"),
                rs.getString("actor_id"),
                rs.getTimestamp("adjusted_at").toInstant()),
        cdcId,
        taskId);
  }

  private static PmartException invalid(String m) {
    return PmartException.invalidState(m);
  }
}
