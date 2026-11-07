package com.pmart.mfc.quality.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 판정·폐기 승인·폐기 실행 표(JDBC). JSON 열은 문자열로 주고받는다. */
@Repository
public class QualityStore {

  public record DecisionRow(
      String decisionId,
      String mfcId,
      String decisionType,
      String state,
      String reason,
      String decidedBy,
      @Nullable String approverId,
      @Nullable String incidentId,
      @Nullable String note,
      @Nullable String declineNote,
      String evidenceRefs,
      String lines,
      @Nullable String inventoryCommitId,
      int appliedQty,
      @Nullable String rejectReason,
      int version,
      Instant createdAt,
      @Nullable Instant approvedAt,
      Instant updatedAt) {}

  public record DisposalRow(
      String disposalApprovalId,
      String mfcId,
      String state,
      String requesterId,
      @Nullable String approverId,
      @Nullable String approvalRef,
      @Nullable String reason,
      @Nullable String sourceRef,
      String lines,
      int version,
      Instant requestedAt,
      @Nullable Instant decidedAt,
      Instant updatedAt) {}

  public record ExecutionRow(
      String executionId,
      String disposalApprovalId,
      String requestKey,
      String state,
      String executedBy,
      String lines,
      int qty,
      @Nullable String note,
      @Nullable String inventoryCommitId,
      @Nullable String rejectReason,
      Instant createdAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public QualityStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 판정 ----------

  public void insertDecision(DecisionRow d) {
    jdbc.update(
        "INSERT INTO quality_decision (decision_id, mfc_id, decision_type, state, reason, decided_by,"
            + " incident_id, note, evidence_refs, lines, version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,1,?,?)",
        d.decisionId(),
        d.mfcId(),
        d.decisionType(),
        d.state(),
        d.reason(),
        d.decidedBy(),
        d.incidentId(),
        d.note(),
        d.evidenceRefs(),
        d.lines(),
        ts(d.createdAt()),
        ts(d.updatedAt()));
  }

  public Optional<DecisionRow> decision(String id) {
    return jdbc
        .query("SELECT * FROM quality_decision WHERE decision_id = ?", (rs, i) -> decision(rs), id)
        .stream()
        .findFirst();
  }

  public Optional<DecisionRow> lockDecision(String id) {
    return jdbc
        .query(
            "SELECT * FROM quality_decision WHERE decision_id = ? FOR UPDATE",
            (rs, i) -> decision(rs),
            id)
        .stream()
        .findFirst();
  }

  /** 해제 승인(REQUESTED → SUBMITTED) 또는 반려(→ DECLINED). */
  public int decide(String id, String state, String approverId, @Nullable String note, Instant at) {
    return jdbc.update(
        "UPDATE quality_decision SET state = ?, approver_id = ?, decline_note = ?, approved_at = ?,"
            + " version = version + 1, updated_at = ? WHERE decision_id = ? AND state = 'REQUESTED'",
        state,
        approverId,
        "DECLINED".equals(state) ? note : null,
        ts(at),
        ts(at),
        id);
  }

  /** 재고 결과 기록(진행 중 상태일 때만). */
  public int finishDecision(
      String id,
      String state,
      @Nullable String commitId,
      int appliedQty,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
        "UPDATE quality_decision SET state = ?, inventory_commit_id = ?, applied_qty = ?,"
            + " reject_reason = ?, version = version + 1, updated_at = ? WHERE decision_id = ?"
            + " AND state IN ('SUBMITTED', 'UNKNOWN_RESULT')",
        state,
        commitId,
        appliedQty,
        reason,
        ts(at),
        id);
  }

  public List<String> openDecisions(Instant before, int limit) {
    return jdbc.queryForList(
        "SELECT decision_id FROM quality_decision WHERE state IN ('SUBMITTED', 'UNKNOWN_RESULT')"
            + " AND updated_at < ? ORDER BY updated_at LIMIT ?",
        String.class,
        ts(before),
        limit);
  }

  // ---------- 폐기 승인 ----------

  public void insertDisposal(DisposalRow d) {
    jdbc.update(
        "INSERT INTO disposal_approval (disposal_approval_id, mfc_id, state, requester_id, reason,"
            + " source_ref, lines, version, requested_at, updated_at) VALUES (?,?,?,?,?,?,?::jsonb,1,?,?)",
        d.disposalApprovalId(),
        d.mfcId(),
        d.state(),
        d.requesterId(),
        d.reason(),
        d.sourceRef(),
        d.lines(),
        ts(d.requestedAt()),
        ts(d.updatedAt()));
  }

  public Optional<DisposalRow> disposal(String id) {
    return jdbc
        .query(
            "SELECT * FROM disposal_approval WHERE disposal_approval_id = ?",
            (rs, i) -> disposal(rs),
            id)
        .stream()
        .findFirst();
  }

  public Optional<DisposalRow> lockDisposal(String id) {
    return jdbc
        .query(
            "SELECT * FROM disposal_approval WHERE disposal_approval_id = ? FOR UPDATE",
            (rs, i) -> disposal(rs),
            id)
        .stream()
        .findFirst();
  }

  public void decideDisposal(
      String id,
      String state,
      String approverId,
      @Nullable String approvalRef,
      String lines,
      Instant at) {
    jdbc.update(
        "UPDATE disposal_approval SET state = ?, approver_id = ?, approval_ref = ?, lines = ?::jsonb,"
            + " decided_at = ?, version = version + 1, updated_at = ? WHERE disposal_approval_id = ?",
        state,
        approverId,
        approvalRef,
        lines,
        ts(at),
        ts(at),
        id);
  }

  public void updateDisposalProgress(String id, String state, String lines, Instant at) {
    jdbc.update(
        "UPDATE disposal_approval SET state = ?, lines = ?::jsonb, version = version + 1,"
            + " updated_at = ? WHERE disposal_approval_id = ?",
        state,
        lines,
        ts(at),
        id);
  }

  // ---------- 폐기 실행 ----------

  public void insertExecution(ExecutionRow e) {
    jdbc.update(
        "INSERT INTO disposal_execution (execution_id, disposal_approval_id, request_key, state,"
            + " executed_by, lines, qty, note, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?,?,?,?)",
        e.executionId(),
        e.disposalApprovalId(),
        e.requestKey(),
        e.state(),
        e.executedBy(),
        e.lines(),
        e.qty(),
        e.note(),
        ts(e.createdAt()),
        ts(e.updatedAt()));
  }

  public Optional<ExecutionRow> executionByKey(String disposalId, String requestKey) {
    return jdbc
        .query(
            "SELECT * FROM disposal_execution WHERE disposal_approval_id = ? AND request_key = ?",
            (rs, i) -> execution(rs),
            disposalId,
            requestKey)
        .stream()
        .findFirst();
  }

  public Optional<ExecutionRow> execution(String executionId) {
    return jdbc
        .query(
            "SELECT * FROM disposal_execution WHERE execution_id = ?",
            (rs, i) -> execution(rs),
            executionId)
        .stream()
        .findFirst();
  }

  public List<ExecutionRow> executions(String disposalId) {
    return jdbc.query(
        "SELECT * FROM disposal_execution WHERE disposal_approval_id = ? ORDER BY created_at",
        (rs, i) -> execution(rs),
        disposalId);
  }

  public int finishExecution(
      String executionId,
      String state,
      @Nullable String commitId,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
        "UPDATE disposal_execution SET state = ?, inventory_commit_id = ?, reject_reason = ?,"
            + " updated_at = ? WHERE execution_id = ? AND state IN ('SUBMITTED', 'UNKNOWN_RESULT')",
        state,
        commitId,
        reason,
        ts(at),
        executionId);
  }

  public List<String> openExecutions(Instant before, int limit) {
    return jdbc.queryForList(
        "SELECT execution_id FROM disposal_execution WHERE state IN ('SUBMITTED', 'UNKNOWN_RESULT')"
            + " AND updated_at < ? ORDER BY updated_at LIMIT ?",
        String.class,
        ts(before),
        limit);
  }

  // ---------- 변환 ----------

  private static DecisionRow decision(ResultSet rs) throws SQLException {
    return new DecisionRow(
        rs.getString("decision_id"),
        rs.getString("mfc_id"),
        rs.getString("decision_type"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("decided_by"),
        rs.getString("approver_id"),
        rs.getString("incident_id"),
        rs.getString("note"),
        rs.getString("decline_note"),
        rs.getString("evidence_refs"),
        rs.getString("lines"),
        rs.getString("inventory_commit_id"),
        rs.getInt("applied_qty"),
        rs.getString("reject_reason"),
        rs.getInt("version"),
        instant(rs, "created_at"),
        instantOrNull(rs, "approved_at"),
        instant(rs, "updated_at"));
  }

  private static DisposalRow disposal(ResultSet rs) throws SQLException {
    return new DisposalRow(
        rs.getString("disposal_approval_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("requester_id"),
        rs.getString("approver_id"),
        rs.getString("approval_ref"),
        rs.getString("reason"),
        rs.getString("source_ref"),
        rs.getString("lines"),
        rs.getInt("version"),
        instant(rs, "requested_at"),
        instantOrNull(rs, "decided_at"),
        instant(rs, "updated_at"));
  }

  private static ExecutionRow execution(ResultSet rs) throws SQLException {
    return new ExecutionRow(
        rs.getString("execution_id"),
        rs.getString("disposal_approval_id"),
        rs.getString("request_key"),
        rs.getString("state"),
        rs.getString("executed_by"),
        rs.getString("lines"),
        rs.getInt("qty"),
        rs.getString("note"),
        rs.getString("inventory_commit_id"),
        rs.getString("reject_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    return rs.getTimestamp(col).toInstant();
  }

  private static @Nullable Instant instantOrNull(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
