package com.pmart.approval.infrastructure;

import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** change_request·approval·approval_decision 저장(JDBC — 본문은 jsonb 스냅샷). 상태 갱신은 버전 조건부. */
@Repository
public class ApprovalStore {

  public record ChangeRequestRow(
      String changeRequestId,
      int version,
      String type,
      String targetService,
      String targetRef,
      String requestState,
      String requesterId,
      @Nullable String approvalId,
      String body,
      Instant createdAt,
      @Nullable Instant submittedAt) {}

  public record ApprovalRow(
      String approvalId,
      int version,
      String changeRequestId,
      String type,
      String targetService,
      String targetRef,
      String requesterId,
      String reviewState,
      String executionState,
      String deliveryState,
      String unit,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      Instant submittedAt) {}

  public record DecisionRow(
      String decisionId,
      String approvalId,
      String decision,
      String approverId,
      String body,
      Instant decidedAt) {}

  private final JdbcTemplate jdbc;

  public ApprovalStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ChangeRequestRow r) {
    jdbc.update(
        "INSERT INTO change_request (change_request_id, version, type, target_service, target_ref,"
            + " request_state, requester_id, approval_id, body, created_at, submitted_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        r.changeRequestId(),
        r.version(),
        r.type(),
        r.targetService(),
        r.targetRef(),
        r.requestState(),
        r.requesterId(),
        r.approvalId(),
        r.body(),
        ts(r.createdAt()),
        ts(r.submittedAt()));
  }

  public Optional<ChangeRequestRow> findChangeRequest(String id) {
    return jdbc
        .query(
            "SELECT * FROM change_request WHERE change_request_id = ?",
            (rs, n) -> changeRequest(rs),
            id)
        .stream()
        .findFirst();
  }

  /** 제출 반영. 버전이 바뀌었으면 VERSION_CONFLICT. */
  public void markSubmitted(String id, int expectedVersion, String approvalId, Instant at) {
    int n =
        jdbc.update(
            "UPDATE change_request SET request_state = 'SUBMITTED', approval_id = ?,"
                + " submitted_at = ?, version = version + 1"
                + " WHERE change_request_id = ? AND version = ?",
            approvalId,
            ts(at),
            id,
            expectedVersion);
    if (n == 0) {
      throw PmartException.versionConflict("변경 요청이 다른 요청으로 먼저 바뀌었습니다. 다시 조회하세요.");
    }
  }

  /** 같은 대상에 심사 중인 승인이 있으면 DUPLICATE_APPROVAL(409, 이중 발급 금지). */
  public void insert(ApprovalRow r) {
    try {
      jdbc.update(
          "INSERT INTO approval (approval_id, version, change_request_id, type, target_service,"
              + " target_ref, requester_id, review_state, execution_state, delivery_state, unit,"
              + " effective_from, expires_at, submitted_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
          r.approvalId(),
          r.version(),
          r.changeRequestId(),
          r.type(),
          r.targetService(),
          r.targetRef(),
          r.requesterId(),
          r.reviewState(),
          r.executionState(),
          r.deliveryState(),
          r.unit(),
          ts(r.effectiveFrom()),
          ts(r.expiresAt()),
          ts(r.submittedAt()));
    } catch (DuplicateKeyException e) {
      throw new PmartException(
          "DUPLICATE_APPROVAL",
          HttpStatus.CONFLICT,
          "같은 대상에 심사 중인 승인이 이미 있습니다(이중 발급 금지).",
          false,
          e);
    }
  }

  public Optional<ApprovalRow> findApproval(String id) {
    return jdbc
        .query("SELECT * FROM approval WHERE approval_id = ?", (rs, n) -> approval(rs), id)
        .stream()
        .findFirst();
  }

  /** 심사 결정 반영. 승인이면 실행 축을 VALIDATING 으로(정본 반영 대기). 버전이 바뀌었으면 VERSION_CONFLICT. */
  public void markDecided(
      String approvalId,
      int expectedVersion,
      String reviewState,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt) {
    int n =
        jdbc.update(
            "UPDATE approval SET review_state = ?, execution_state = ?, effective_from = ?,"
                + " expires_at = ?, version = version + 1 WHERE approval_id = ? AND version = ?",
            reviewState,
            "APPROVED".equals(reviewState) ? "VALIDATING" : "NOT_REQUESTED",
            ts(effectiveFrom),
            ts(expiresAt),
            approvalId,
            expectedVersion);
    if (n == 0) {
      throw PmartException.versionConflict("승인이 다른 요청으로 먼저 바뀌었습니다. 다시 조회하세요.");
    }
  }

  public void insert(DecisionRow r) {
    jdbc.update(
        "INSERT INTO approval_decision (decision_id, approval_id, decision, approver_id, body,"
            + " decided_at) VALUES (?,?,?,?,?::jsonb,?)",
        r.decisionId(),
        r.approvalId(),
        r.decision(),
        r.approverId(),
        r.body(),
        ts(r.decidedAt()));
  }

  public List<DecisionRow> decisions(String approvalId) {
    return jdbc.query(
        "SELECT * FROM approval_decision WHERE approval_id = ? ORDER BY decided_at, decision_id",
        (rs, n) -> decision(rs),
        approvalId);
  }

  public Optional<DecisionRow> findDecision(String approvalId, String decisionId) {
    return jdbc
        .query(
            "SELECT * FROM approval_decision WHERE approval_id = ? AND decision_id = ?",
            (rs, n) -> decision(rs),
            approvalId,
            decisionId)
        .stream()
        .findFirst();
  }

  /** 승인함: 최근 제출 순, (submitted_at, approval_id) 키셋 페이지. */
  public List<ApprovalRow> list(
      @Nullable String type,
      @Nullable String reviewState,
      @Nullable String executionState,
      @Nullable String requesterId,
      @Nullable String targetRef,
      @Nullable Instant afterSubmittedAt,
      @Nullable String afterId,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM approval WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type);
    }
    if (reviewState != null) {
      sql.append(" AND review_state = ?");
      args.add(reviewState);
    }
    if (executionState != null) {
      sql.append(" AND execution_state = ?");
      args.add(executionState);
    }
    if (requesterId != null) {
      sql.append(" AND requester_id = ?");
      args.add(requesterId);
    }
    if (targetRef != null) {
      sql.append(" AND target_ref = ?");
      args.add(targetRef);
    }
    if (afterSubmittedAt != null && afterId != null) {
      sql.append(" AND (submitted_at, approval_id) < (?, ?)");
      args.add(ts(afterSubmittedAt));
      args.add(afterId);
    }
    sql.append(" ORDER BY submitted_at DESC, approval_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, n) -> approval(rs), args.toArray());
  }

  private static ChangeRequestRow changeRequest(ResultSet rs) throws SQLException {
    return new ChangeRequestRow(
        rs.getString("change_request_id"),
        rs.getInt("version"),
        rs.getString("type"),
        rs.getString("target_service"),
        rs.getString("target_ref"),
        rs.getString("request_state"),
        rs.getString("requester_id"),
        rs.getString("approval_id"),
        rs.getString("body"),
        instant(rs, "created_at"),
        instantOrNull(rs, "submitted_at"));
  }

  private static ApprovalRow approval(ResultSet rs) throws SQLException {
    return new ApprovalRow(
        rs.getString("approval_id"),
        rs.getInt("version"),
        rs.getString("change_request_id"),
        rs.getString("type"),
        rs.getString("target_service"),
        rs.getString("target_ref"),
        rs.getString("requester_id"),
        rs.getString("review_state"),
        rs.getString("execution_state"),
        rs.getString("delivery_state"),
        rs.getString("unit"),
        instantOrNull(rs, "effective_from"),
        instantOrNull(rs, "expires_at"),
        instant(rs, "submitted_at"));
  }

  private static DecisionRow decision(ResultSet rs) throws SQLException {
    return new DecisionRow(
        rs.getString("decision_id"),
        rs.getString("approval_id"),
        rs.getString("decision"),
        rs.getString("approver_id"),
        rs.getString("body"),
        instant(rs, "decided_at"));
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    return rs.getTimestamp(col).toInstant();
  }

  private static @Nullable Instant instantOrNull(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
