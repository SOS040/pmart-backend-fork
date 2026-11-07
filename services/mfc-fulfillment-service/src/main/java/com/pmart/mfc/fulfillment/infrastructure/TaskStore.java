package com.pmart.mfc.fulfillment.infrastructure;

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

/** 점원 단말 작업·시작·실적·포장(V4, JDBC). 작업 지시·출고 머리는 {@link WorkOrderStore}. */
@Repository
public class TaskStore {

  /** 작업 + 출고·작업 지시 머리. */
  public record TaskInfo(
      String taskId,
      String shipmentId,
      String taskType,
      String state,
      int version,
      @Nullable String workerId,
      @Nullable String assignmentId,
      int assignmentVersion,
      @Nullable String startRequestId,
      @Nullable String startedBy,
      @Nullable Instant startedAt,
      @Nullable String deviceId,
      @Nullable String blockReason,
      @Nullable Instant readyAt,
      Instant createdAt,
      String fulfillmentId,
      String orderId,
      String mfcId,
      String reservationId,
      @Nullable Instant arrivalStart,
      @Nullable Instant preparationStartedAt,
      String shipmentState) {}

  /** 작업 행 + 출고 행 사본(SKU·상품명). */
  public record LineView(
      String orderLineId,
      String sku,
      @Nullable String productName,
      int plannedQty,
      int completedQty,
      int cancelledQty) {

    public int remaining() {
      return plannedQty - completedQty - cancelledQty;
    }
  }

  public record StartRow(
      String taskId,
      String requestId,
      String outcome,
      @Nullable String rejectCode,
      String workerId,
      int assignmentVersion,
      int taskVersion,
      Instant decidedAt) {}

  public record ExecutionRow(
      String executionId,
      String taskId,
      String requestId,
      String workerId,
      String executionType,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      String lines,
      String state,
      @Nullable String inventoryCommitId,
      int appliedQty,
      @Nullable String rejectReason,
      Instant recordedAt) {}

  public record PackageRow(
      String packageId,
      String shipmentId,
      String requestId,
      int packageVersion,
      String state,
      @Nullable String qrCode,
      String contents,
      int bagCount,
      boolean containsAlcohol,
      @Nullable String temperatureZone,
      @Nullable Integer weightG,
      @Nullable Integer volumeCm3,
      String packedBy,
      String packingLocation,
      @Nullable String inventoryCommitId,
      @Nullable String rejectReason,
      int version,
      Instant createdAt,
      @Nullable Instant sealedAt) {}

  private static final String TASK_SELECT =
      "SELECT t.*, s.fulfillment_id, s.state AS shipment_state, w.order_id, w.mfc_id,"
          + " w.reservation_id, w.arrival_start, w.preparation_started_at FROM task t"
          + " JOIN shipment s ON s.shipment_id = t.shipment_id"
          + " JOIN work_order w ON w.fulfillment_id = s.fulfillment_id";

  private final JdbcTemplate jdbc;

  public TaskStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 작업 ----------

  public Optional<TaskInfo> find(String taskId) {
    return jdbc.query(TASK_SELECT + " WHERE t.task_id = ?", (rs, i) -> info(rs), taskId).stream()
        .findFirst();
  }

  /** 작업 행을 잠그고 읽는다. */
  public Optional<TaskInfo> lock(String taskId) {
    return jdbc
        .query(TASK_SELECT + " WHERE t.task_id = ? FOR UPDATE OF t", (rs, i) -> info(rs), taskId)
        .stream()
        .findFirst();
  }

  public List<TaskInfo> list(
      String mfcId,
      @Nullable String workerId,
      @Nullable String type,
      List<String> states,
      @Nullable String afterTaskId,
      int limit) {
    StringBuilder sql = new StringBuilder(TASK_SELECT + " WHERE w.mfc_id = ?");
    List<Object> args = new ArrayList<>(List.of(mfcId));
    if (workerId != null) {
      sql.append(" AND t.worker_id = ?");
      args.add(workerId);
    }
    if (type != null) {
      sql.append(" AND t.task_type = ?");
      args.add(type);
    }
    if (!states.isEmpty()) {
      sql.append(" AND t.state IN (")
          .append(String.join(",", states.stream().map(s -> "?").toList()))
          .append(")");
      args.addAll(states);
    }
    if (afterTaskId != null) {
      sql.append(
          " AND (t.created_at, t.task_id) > (SELECT created_at, task_id FROM task WHERE task_id = ?)");
      args.add(afterTaskId);
    }
    sql.append(" ORDER BY t.created_at, t.task_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> info(rs), args.toArray());
  }

  public List<LineView> lines(String taskId) {
    return jdbc.query(
        "SELECT l.order_line_id, s.sku, s.product_name, l.planned_qty, l.completed_qty,"
            + " l.cancelled_qty FROM task_line l JOIN shipment_line s ON s.shipment_id = l.shipment_id"
            + " AND s.order_line_id = l.order_line_id WHERE l.task_id = ? ORDER BY l.order_line_id",
        (rs, i) ->
            new LineView(
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getInt("planned_qty"),
                rs.getInt("completed_qty"),
                rs.getInt("cancelled_qty")),
        taskId);
  }

  public Optional<TaskInfo> sibling(String shipmentId, String taskType) {
    return jdbc
        .query(
            TASK_SELECT + " WHERE t.shipment_id = ? AND t.task_type = ?",
            (rs, i) -> info(rs),
            shipmentId,
            taskType)
        .stream()
        .findFirst();
  }

  public void addCompleted(String taskId, String orderLineId, int qty) {
    jdbc.update(
        "UPDATE task_line SET completed_qty = completed_qty + ? WHERE task_id = ? AND order_line_id = ?",
        qty,
        taskId,
        orderLineId);
  }

  /** 상태 전이(+버전). clearStart 면 시작 점유를 지운다(재배정·종료). */
  public void setState(
      String taskId, String state, @Nullable String blockReason, boolean clearStart, Instant at) {
    jdbc.update(
        "UPDATE task SET state = ?, block_reason = ?, version = version + 1, updated_at = ?"
            + (clearStart
                ? ", start_request_id = NULL, started_by = NULL, started_at = NULL, device_id = NULL"
                : "")
            + " WHERE task_id = ?",
        state,
        blockReason,
        ts(at),
        taskId);
  }

  public void markStarted(
      String taskId, String requestId, String workerId, @Nullable String deviceId, Instant at) {
    jdbc.update(
        "UPDATE task SET state = 'IN_PROGRESS', start_request_id = ?, started_by = ?, started_at = ?,"
            + " device_id = ?, version = version + 1, updated_at = ? WHERE task_id = ?",
        requestId,
        workerId,
        ts(at),
        deviceId,
        ts(at),
        taskId);
  }

  /** 배정 투영: 더 새 배정 버전만. 반환 = 바뀐 행 수. */
  public int applyAssignment(
      String taskId, String workerId, @Nullable String assignmentId, int version, Instant at) {
    return jdbc.update(
        "UPDATE task SET worker_id = ?, assignment_id = ?, assignment_version = ?, updated_at = ?"
            + " WHERE task_id = ? AND assignment_version < ?",
        workerId,
        assignmentId,
        version,
        ts(at),
        taskId,
        version);
  }

  /** 담당이 있는 READY → ASSIGNED. */
  public int assignReady(Instant at) {
    return jdbc.update(
        "UPDATE task SET state = 'ASSIGNED', version = version + 1, updated_at = ?"
            + " WHERE state = 'READY' AND worker_id IS NOT NULL",
        ts(at));
  }

  /** 출고의 PACK 대기 → READY(담당 있으면 ASSIGNED). */
  public void releasePack(String shipmentId, Instant at) {
    jdbc.update(
        "UPDATE task SET state = CASE WHEN worker_id IS NULL THEN 'READY' ELSE 'ASSIGNED' END,"
            + " version = version + 1, updated_at = ? WHERE shipment_id = ? AND task_type = 'PACK'"
            + " AND state = 'WAITING'",
        ts(at),
        shipmentId);
  }

  /** 준비 시작을 처음 한 번만 기록. true = 이번이 처음. */
  public boolean markPreparationStarted(String fulfillmentId, Instant at) {
    return jdbc.update(
            "UPDATE work_order SET preparation_started_at = ?, version = version + 1, updated_at = ?"
                + " WHERE fulfillment_id = ? AND preparation_started_at IS NULL",
            ts(at),
            ts(at),
            fulfillmentId)
        == 1;
  }

  /** 그 주문의 PICK 이 시작됐는가(중지 판정 — 집품 뒤 취소는 IV-08). */
  public boolean pickStarted(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task t JOIN shipment s ON s.shipment_id = t.shipment_id"
                + " JOIN work_order w ON w.fulfillment_id = s.fulfillment_id WHERE w.order_id = ?"
                + " AND t.task_type = 'PICK' AND (t.started_at IS NOT NULL"
                + " OR EXISTS (SELECT 1 FROM task_line l WHERE l.task_id = t.task_id AND l.completed_qty > 0))",
            Integer.class,
            orderId);
    return n != null && n > 0;
  }

  // ---------- 시작 판정 ----------

  public Optional<StartRow> findStart(String taskId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM task_start WHERE task_id = ? AND request_id = ?",
            (rs, i) ->
                new StartRow(
                    rs.getString("task_id"),
                    rs.getString("request_id"),
                    rs.getString("outcome"),
                    rs.getString("reject_code"),
                    rs.getString("worker_id"),
                    rs.getInt("assignment_version"),
                    rs.getInt("task_version"),
                    rs.getTimestamp("decided_at").toInstant()),
            taskId,
            requestId)
        .stream()
        .findFirst();
  }

  public void insertStart(StartRow r) {
    jdbc.update(
        "INSERT INTO task_start (task_id, request_id, outcome, reject_code, worker_id,"
            + " assignment_version, task_version, decided_at) VALUES (?,?,?,?,?,?,?,?)",
        r.taskId(),
        r.requestId(),
        r.outcome(),
        r.rejectCode(),
        r.workerId(),
        r.assignmentVersion(),
        r.taskVersion(),
        ts(r.decidedAt()));
  }

  // ---------- 실적 ----------

  public Optional<ExecutionRow> findExecution(String taskId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM task_execution WHERE task_id = ? AND request_id = ?",
            (rs, i) -> execution(rs),
            taskId,
            requestId)
        .stream()
        .findFirst();
  }

  public Optional<ExecutionRow> execution(String executionId) {
    return jdbc
        .query(
            "SELECT * FROM task_execution WHERE execution_id = ?",
            (rs, i) -> execution(rs),
            executionId)
        .stream()
        .findFirst();
  }

  public List<ExecutionRow> openExecutions(String taskId) {
    return jdbc.query(
        "SELECT * FROM task_execution WHERE task_id = ? AND state IN ('RECORDED', 'UNKNOWN_RESULT')"
            + " ORDER BY recorded_at",
        (rs, i) -> execution(rs),
        taskId);
  }

  public void insertExecution(ExecutionRow r) {
    jdbc.update(
        "INSERT INTO task_execution (execution_id, task_id, request_id, worker_id, execution_type,"
            + " from_location, to_location, lines, state, applied_qty, recorded_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",
        r.executionId(),
        r.taskId(),
        r.requestId(),
        r.workerId(),
        r.executionType(),
        r.fromLocation(),
        r.toLocation(),
        r.lines(),
        r.state(),
        r.appliedQty(),
        ts(r.recordedAt()),
        ts(r.recordedAt()));
  }

  /** 열린 실적만(RECORDED·UNKNOWN_RESULT) 바꾼다. 반환 = 바뀐 행 수(이미 끝났으면 0). */
  public int finishExecution(
      String executionId,
      String state,
      @Nullable String commitId,
      int appliedQty,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
        "UPDATE task_execution SET state = ?, inventory_commit_id = ?, applied_qty = ?,"
            + " reject_reason = ?, updated_at = ? WHERE execution_id = ?"
            + " AND state IN ('RECORDED', 'UNKNOWN_RESULT')",
        state,
        commitId,
        appliedQty,
        reason,
        ts(at),
        executionId);
  }

  /** 출고의 집품분이 놓인 포장 위치(반영된 PICK 실적의 도착지). */
  public List<String> packingLocations(String shipmentId) {
    return jdbc.queryForList(
        "SELECT DISTINCT e.to_location FROM task_execution e JOIN task t ON t.task_id = e.task_id"
            + " WHERE t.shipment_id = ? AND e.execution_type = 'PICK' AND e.state = 'APPLIED'"
            + " AND e.to_location IS NOT NULL AND e.applied_qty > 0 ORDER BY 1",
        String.class,
        shipmentId);
  }

  // ---------- 포장 ----------

  public Optional<PackageRow> findPackage(String shipmentId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM package WHERE shipment_id = ? AND request_id = ?",
            (rs, i) -> pkg(rs),
            shipmentId,
            requestId)
        .stream()
        .findFirst();
  }

  public Optional<PackageRow> pkg(String packageId) {
    return jdbc
        .query("SELECT * FROM package WHERE package_id = ?", (rs, i) -> pkg(rs), packageId)
        .stream()
        .findFirst();
  }

  public List<PackageRow> packages(String shipmentId) {
    return jdbc.query(
        "SELECT * FROM package WHERE shipment_id = ? ORDER BY created_at, package_id",
        (rs, i) -> pkg(rs),
        shipmentId);
  }

  /** 열린(OPEN) 포장의 주문 줄별 수량 — 남은 포장량 계산에서 뺀다. */
  public Map<String, Integer> openPackageQty(String shipmentId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT c->>'orderLineId' AS line, SUM((c->>'qty')::int) AS qty FROM package p,"
            + " jsonb_array_elements(p.contents) c WHERE p.shipment_id = ? AND p.state = 'OPEN'"
            + " GROUP BY 1",
        rs -> {
          out.put(rs.getString("line"), rs.getInt("qty"));
        },
        shipmentId);
    return out;
  }

  public void insertPackage(PackageRow p) {
    jdbc.update(
        "INSERT INTO package (package_id, shipment_id, request_id, package_version, state, contents,"
            + " bag_count, contains_alcohol, temperature_zone, weight_g, volume_cm3, packed_by,"
            + " packing_location, version, created_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?)",
        p.packageId(),
        p.shipmentId(),
        p.requestId(),
        p.packageVersion(),
        p.state(),
        p.contents(),
        p.bagCount(),
        p.containsAlcohol(),
        p.temperatureZone(),
        p.weightG(),
        p.volumeCm3(),
        p.packedBy(),
        p.packingLocation(),
        p.version(),
        ts(p.createdAt()));
  }

  /** OPEN → SEALED / VOIDED. 반환 = 바뀐 행 수. */
  public int finishPackage(
      String packageId,
      String state,
      @Nullable String qrCode,
      @Nullable String commitId,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
        "UPDATE package SET state = ?, qr_code = ?, inventory_commit_id = ?, reject_reason = ?,"
            + " sealed_at = CASE WHEN ? = 'SEALED' THEN ? ELSE sealed_at END, version = version + 1"
            + " WHERE package_id = ? AND state = 'OPEN'",
        state,
        qrCode,
        commitId,
        reason,
        state,
        ts(at),
        packageId);
  }

  public void shipmentReady(String shipmentId, Instant at) {
    jdbc.update(
        "UPDATE shipment SET state = 'READY', ready_at = ?, version = version + 1, updated_at = ?"
            + " WHERE shipment_id = ? AND state = 'PREPARING'",
        ts(at),
        ts(at),
        shipmentId);
  }

  public @Nullable Instant shipmentReadyAt(String shipmentId) {
    return jdbc
        .query(
            "SELECT ready_at FROM shipment WHERE shipment_id = ?",
            (rs, i) -> instant(rs, "ready_at"),
            shipmentId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  // ---------- 변환 ----------

  private static TaskInfo info(ResultSet rs) throws SQLException {
    return new TaskInfo(
        rs.getString("task_id"),
        rs.getString("shipment_id"),
        rs.getString("task_type"),
        rs.getString("state"),
        rs.getInt("version"),
        rs.getString("worker_id"),
        rs.getString("assignment_id"),
        rs.getInt("assignment_version"),
        rs.getString("start_request_id"),
        rs.getString("started_by"),
        instant(rs, "started_at"),
        rs.getString("device_id"),
        rs.getString("block_reason"),
        instant(rs, "ready_at"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("fulfillment_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("reservation_id"),
        instant(rs, "arrival_start"),
        instant(rs, "preparation_started_at"),
        rs.getString("shipment_state"));
  }

  private static ExecutionRow execution(ResultSet rs) throws SQLException {
    return new ExecutionRow(
        rs.getString("execution_id"),
        rs.getString("task_id"),
        rs.getString("request_id"),
        rs.getString("worker_id"),
        rs.getString("execution_type"),
        rs.getString("from_location"),
        rs.getString("to_location"),
        rs.getString("lines"),
        rs.getString("state"),
        rs.getString("inventory_commit_id"),
        rs.getInt("applied_qty"),
        rs.getString("reject_reason"),
        rs.getTimestamp("recorded_at").toInstant());
  }

  private static PackageRow pkg(ResultSet rs) throws SQLException {
    return new PackageRow(
        rs.getString("package_id"),
        rs.getString("shipment_id"),
        rs.getString("request_id"),
        rs.getInt("package_version"),
        rs.getString("state"),
        rs.getString("qr_code"),
        rs.getString("contents"),
        rs.getInt("bag_count"),
        rs.getBoolean("contains_alcohol"),
        rs.getString("temperature_zone"),
        (Integer) rs.getObject("weight_g"),
        (Integer) rs.getObject("volume_cm3"),
        rs.getString("packed_by"),
        rs.getString("packing_location"),
        rs.getString("inventory_commit_id"),
        rs.getString("reject_reason"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "sealed_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
