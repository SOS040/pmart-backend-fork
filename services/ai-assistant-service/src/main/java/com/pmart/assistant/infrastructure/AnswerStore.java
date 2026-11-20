package com.pmart.assistant.infrastructure;

import com.pmart.assistant.infrastructure.SnapshotStore.SnapshotRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 답변 기록 = 요청 선점(claim/lease)·처리 중·완료. 공통 request_record 를 쓰지 않는다 — 모델 호출을 긴 트랜잭션 밖에 두기 위해(검토 4).
 *
 * <p>선점 뒤의 모든 쓰기(스냅숏 고정·완료·실패)는 {@code WHERE answer_id=? AND attempt=? AND state='PROCESSING'}
 * 조건부다. 0건이면 다른 작업자가 lease 를 가져간 것이므로, 호출자는 자기 결과를 버리고 행을 다시 읽어 저장 상태로 응답한다.
 */
@Repository
public class AnswerStore {

  public enum State {
    PROCESSING,
    COMPLETED,
    FAILED_RETRYABLE
  }

  public record AnswerRow(
      String answerId,
      String sourceSystem,
      String askerSubject,
      String requestId,
      String requestHash,
      State state,
      @Nullable Instant leaseUntil,
      int attempt,
      @Nullable String lastErrorCode,
      @Nullable String resultState,
      @Nullable String answer,
      @Nullable String citationsJson,
      @Nullable String snapshotId,
      @Nullable String answerMode,
      @Nullable String retrievalMode,
      @Nullable String chatModel,
      @Nullable String embeddingModel,
      @Nullable String question,
      Instant createdAt,
      @Nullable Instant completedAt,
      String sessionId) {

    public boolean leaseValid(Instant now) {
      return state == State.PROCESSING && leaseUntil != null && leaseUntil.isAfter(now);
    }
  }

  /** 완료 기록 내용. */
  public record Completion(
      String resultState,
      String answer,
      String citationsJson,
      @Nullable String snapshotId,
      String answerMode,
      String retrievalMode,
      @Nullable String chatModel,
      @Nullable String embeddingModel) {}

  /** 채팅 세션(ADR-0084). */
  public record SessionRow(
      String sessionId,
      String sourceSystem,
      String ownerSubject,
      Instant createdAt,
      Instant lastAskedAt) {}

  /**
   * 세션 목록 한 줄.
   *
   * @param firstQuestion 첫 질문 원문(저장했을 때만)
   * @param firstAnswer 첫 답변(완료됐을 때만) — 질문이 없을 때 제목 대신
   */
  public record SessionSummary(
      SessionRow session,
      @Nullable String firstQuestion,
      @Nullable String firstAnswer,
      int questionCount) {}

  /** 앞 대화 한 턴(질문 원문이 저장된 완료 답변만). */
  public record Turn(String question, String answer) {}

  public sealed interface PinResult {
    record Pinned(SnapshotRow snapshot) implements PinResult {}

    record NoActiveIndex() implements PinResult {}

    record LeaseLost() implements PinResult {}
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public AnswerStore(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** 새 선점 + 새 세션(세션 ID = 답변 ID 의 SES- 짝). 시험·도구용 — 질문 흐름은 아래 세션 인자 판을 쓴다. */
  public boolean insertClaim(
      String answerId,
      String sourceSystem,
      String subject,
      String requestId,
      String requestHash,
      Instant leaseUntil,
      @Nullable String question,
      Instant now) {
    return insertClaim(
        answerId,
        sourceSystem,
        subject,
        requestId,
        requestHash,
        leaseUntil,
        question,
        now,
        "SES-" + answerId.substring(answerId.indexOf('-') + 1),
        true);
  }

  /**
   * 새 선점. 같은 (출처, 주체, 키) 행이 이미 있으면 false. 한 TX 에서 답변 행을 넣고, 넣었으면 새 세션을 만들거나({@code newSession}) 기존
   * 세션의 마지막 질문 시각을 올린다 — 선점에 지면 세션을 만들지 않는다.
   */
  public boolean insertClaim(
      String answerId,
      String sourceSystem,
      String subject,
      String requestId,
      String requestHash,
      Instant leaseUntil,
      @Nullable String question,
      Instant now,
      String sessionId,
      boolean newSession) {
    Boolean inserted =
        tx.execute(
            st -> {
              int n;
              try {
                n =
                    jdbc.update(
                        """
                        INSERT INTO policy_answer (answer_id, source_system, asker_subject, request_id, request_hash,
                          state, lease_until, attempt, question, created_at, session_id)
                        VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, 1, ?, ?, ?)
                        ON CONFLICT (source_system, asker_subject, request_id) DO NOTHING
                        """,
                        answerId,
                        sourceSystem,
                        subject,
                        requestId,
                        requestHash,
                        Timestamp.from(leaseUntil),
                        question,
                        Timestamp.from(now),
                        sessionId);
              } catch (DuplicateKeyException e) {
                return false;
              }
              if (n != 1) {
                return false;
              }
              if (newSession) {
                jdbc.update(
                    """
                    INSERT INTO chat_session (session_id, source_system, owner_subject, created_at, last_asked_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    sessionId,
                    sourceSystem,
                    subject,
                    Timestamp.from(now),
                    Timestamp.from(now));
              } else {
                jdbc.update(
                    "UPDATE chat_session SET last_asked_at = GREATEST(last_asked_at, ?) WHERE session_id = ?",
                    Timestamp.from(now),
                    sessionId);
              }
              return true;
            });
    return Boolean.TRUE.equals(inserted);
  }

  /** 만료된 PROCESSING 또는 FAILED_RETRYABLE 을 다시 선점(attempt+1). 같은 행을 두 요청이 동시에 노려도 하나만 1건. */
  public boolean reclaim(String answerId, int expectedAttempt, Instant leaseUntil, Instant now) {
    return jdbc.update(
            """
            UPDATE policy_answer SET state = 'PROCESSING', attempt = attempt + 1, lease_until = ?,
              last_error_code = NULL, snapshot_id = NULL
            WHERE answer_id = ? AND attempt = ?
              AND (state = 'FAILED_RETRYABLE' OR (state = 'PROCESSING' AND lease_until <= ?))
            """,
            Timestamp.from(leaseUntil),
            answerId,
            expectedAttempt,
            Timestamp.from(now))
        == 1;
  }

  /**
   * 스냅숏 고정(3차 검토 3 + 최종 지시 2). 한 TX 에서 스냅숏 전환 잠금을 <b>공유</b>로 잡고, 활성 스냅숏을 읽고, 선점 조건부로 기록한다. 0건이면
   * lease 를 잃은 것 — 처리 중단.
   */
  public PinResult pinActiveSnapshot(String answerId, int attempt, SnapshotStore snapshots) {
    return tx.execute(
        s -> {
          jdbc.queryForObject(
              "SELECT 1 FROM (SELECT pg_advisory_xact_lock_shared(?)) l",
              Integer.class,
              AdvisoryLocks.SNAPSHOT);
          Optional<SnapshotRow> active = snapshots.findActive();
          if (active.isEmpty()) {
            return new PinResult.NoActiveIndex();
          }
          int n =
              jdbc.update(
                  """
                  UPDATE policy_answer SET snapshot_id = ?
                  WHERE answer_id = ? AND attempt = ? AND state = 'PROCESSING'
                  """,
                  active.get().snapshotId(),
                  answerId,
                  attempt);
          return n == 1 ? new PinResult.Pinned(active.get()) : new PinResult.LeaseLost();
        });
  }

  /** 완료 기록(선점 조건부). false 면 lease 상실 — 자기 답을 돌려주지 않는다. */
  public boolean complete(String answerId, int attempt, Completion c, Instant now) {
    return jdbc.update(
            """
            UPDATE policy_answer SET state = 'COMPLETED', lease_until = NULL, last_error_code = NULL,
              result_state = ?, answer = ?, citations = ?::jsonb, snapshot_id = ?, answer_mode = ?, retrieval_mode = ?,
              chat_model = ?, embedding_model = ?, completed_at = ?
            WHERE answer_id = ? AND attempt = ? AND state = 'PROCESSING'
            """,
            c.resultState(),
            c.answer(),
            c.citationsJson(),
            c.snapshotId(),
            c.answerMode(),
            c.retrievalMode(),
            c.chatModel(),
            c.embeddingModel(),
            Timestamp.from(now),
            answerId,
            attempt)
        == 1;
  }

  /** 실패 기록(선점 조건부). errorCode = CONFIG_REQUIRED(422) | DEPENDENCY_UNAVAILABLE(503). */
  public boolean fail(String answerId, int attempt, String errorCode) {
    return jdbc.update(
            """
            UPDATE policy_answer SET state = 'FAILED_RETRYABLE', last_error_code = ?, lease_until = NULL
            WHERE answer_id = ? AND attempt = ? AND state = 'PROCESSING'
            """,
            errorCode,
            answerId,
            attempt)
        == 1;
  }

  public Optional<AnswerRow> findByRequest(String sourceSystem, String subject, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM policy_answer WHERE source_system = ? AND asker_subject = ? AND request_id = ?",
            AnswerStore::row,
            sourceSystem,
            subject,
            requestId)
        .stream()
        .findFirst();
  }

  public Optional<AnswerRow> findById(String answerId) {
    return jdbc
        .query("SELECT * FROM policy_answer WHERE answer_id = ?", AnswerStore::row, answerId)
        .stream()
        .findFirst();
  }

  /**
   * 내 질문 목록(BR-46): (출처, 주체) 범위, 최근 순. 커서 = (created_at, answer_id) 이전.
   *
   * @param limit 한 번에 읽을 행 수(호출자가 size+1 로 다음 여부를 판단)
   */
  public List<AnswerRow> listMine(
      String sourceSystem,
      String subject,
      @Nullable Instant beforeCreatedAt,
      @Nullable String beforeAnswerId,
      int limit) {
    if (beforeCreatedAt == null || beforeAnswerId == null) {
      return jdbc.query(
          "SELECT * FROM policy_answer WHERE source_system = ? AND asker_subject = ?"
              + " ORDER BY created_at DESC, answer_id DESC LIMIT ?",
          AnswerStore::row,
          sourceSystem,
          subject,
          limit);
    }
    return jdbc.query(
        "SELECT * FROM policy_answer WHERE source_system = ? AND asker_subject = ?"
            + " AND (created_at, answer_id) < (?, ?) ORDER BY created_at DESC, answer_id DESC LIMIT ?",
        AnswerStore::row,
        sourceSystem,
        subject,
        Timestamp.from(beforeCreatedAt),
        beforeAnswerId,
        limit);
  }

  // ---- 채팅 세션(ADR-0084) ----

  public Optional<SessionRow> findSession(String sessionId) {
    return jdbc
        .query("SELECT * FROM chat_session WHERE session_id = ?", AnswerStore::session, sessionId)
        .stream()
        .findFirst();
  }

  /**
   * 내 세션 목록: (출처, 주체) 범위, 마지막 질문 최근 순. 커서 = (last_asked_at, session_id) 이전. 첫 질문·첫 답변·질문 수를 함께.
   *
   * @param limit 한 번에 읽을 행 수(호출자가 size+1 로 다음 여부를 판단)
   */
  public List<SessionSummary> listSessions(
      String sourceSystem,
      String subject,
      @Nullable Instant beforeLastAskedAt,
      @Nullable String beforeSessionId,
      int limit) {
    String sql =
        """
        SELECT s.*, f.question AS first_question, f.answer AS first_answer,
          (SELECT count(*) FROM policy_answer c WHERE c.session_id = s.session_id) AS question_count
        FROM chat_session s
        LEFT JOIN LATERAL (
          SELECT a.question, a.answer FROM policy_answer a WHERE a.session_id = s.session_id
          ORDER BY a.created_at, a.answer_id LIMIT 1) f ON true
        WHERE s.source_system = ? AND s.owner_subject = ?
        """;
    if (beforeLastAskedAt == null || beforeSessionId == null) {
      return jdbc.query(
          sql + " ORDER BY s.last_asked_at DESC, s.session_id DESC LIMIT ?",
          AnswerStore::summary,
          sourceSystem,
          subject,
          limit);
    }
    return jdbc.query(
        sql
            + " AND (s.last_asked_at, s.session_id) < (?, ?)"
            + " ORDER BY s.last_asked_at DESC, s.session_id DESC LIMIT ?",
        AnswerStore::summary,
        sourceSystem,
        subject,
        Timestamp.from(beforeLastAskedAt),
        beforeSessionId,
        limit);
  }

  /** 세션의 답변(질문 순). */
  public List<AnswerRow> sessionAnswers(String sessionId) {
    return jdbc.query(
        "SELECT * FROM policy_answer WHERE session_id = ? ORDER BY created_at, answer_id",
        AnswerStore::row,
        sessionId);
  }

  /** 앞 대화: 이 답변보다 먼저 만든, 같은 세션의 완료·근거 있는 답변 중 질문 원문이 있는 것. 오래된 순으로 최대 {@code limit} 턴. */
  public List<Turn> previousTurns(String sessionId, String answerId, int limit) {
    List<Turn> recent =
        jdbc.query(
            """
            SELECT p.question, p.answer FROM policy_answer p, policy_answer me
            WHERE me.answer_id = ? AND p.session_id = ? AND p.answer_id <> me.answer_id
              AND p.created_at <= me.created_at AND p.state = 'COMPLETED' AND p.result_state = 'ANSWERED'
              AND p.question IS NOT NULL AND p.answer IS NOT NULL
            ORDER BY p.created_at DESC, p.answer_id DESC LIMIT ?
            """,
            (rs, i) -> new Turn(rs.getString(1), rs.getString(2)),
            answerId,
            sessionId,
            limit);
    return recent.reversed();
  }

  /** 본인 세션 삭제(답변도 함께). 본인 세션이 아니거나 없으면 0. */
  public int deleteSession(String sessionId, String sourceSystem, String subject) {
    return jdbc.update(
        "DELETE FROM chat_session WHERE session_id = ? AND source_system = ? AND owner_subject = ?",
        sessionId,
        sourceSystem,
        subject);
  }

  /** 보존 정리: 마지막 질문이 기준 시각보다 앞선 세션(답변도 함께). 처리 중 답변이 있는 세션은 지우지 않는다. */
  public int deleteSessionsLastAskedBefore(Instant cutoff) {
    return jdbc.update(
        """
        DELETE FROM chat_session s WHERE s.last_asked_at < ?
          AND NOT EXISTS (SELECT 1 FROM policy_answer a WHERE a.session_id = s.session_id
                          AND a.state = 'PROCESSING')
        """,
        Timestamp.from(cutoff));
  }

  /** 시험·운영 확인용: 특정 상태 행 수. */
  public List<AnswerRow> findAllBySubject(String subject) {
    return jdbc.query(
        "SELECT * FROM policy_answer WHERE asker_subject = ? ORDER BY created_at",
        AnswerStore::row,
        subject);
  }

  private static AnswerRow row(ResultSet rs, int n) throws SQLException {
    return new AnswerRow(
        rs.getString("answer_id"),
        rs.getString("source_system"),
        rs.getString("asker_subject"),
        rs.getString("request_id"),
        rs.getString("request_hash"),
        State.valueOf(rs.getString("state")),
        SnapshotStore.instant(rs.getTimestamp("lease_until")),
        rs.getInt("attempt"),
        rs.getString("last_error_code"),
        rs.getString("result_state"),
        rs.getString("answer"),
        rs.getString("citations"),
        rs.getString("snapshot_id"),
        rs.getString("answer_mode"),
        rs.getString("retrieval_mode"),
        rs.getString("chat_model"),
        rs.getString("embedding_model"),
        rs.getString("question"),
        rs.getTimestamp("created_at").toInstant(),
        SnapshotStore.instant(rs.getTimestamp("completed_at")),
        rs.getString("session_id"));
  }

  private static SessionRow session(ResultSet rs, int n) throws SQLException {
    return new SessionRow(
        rs.getString("session_id"),
        rs.getString("source_system"),
        rs.getString("owner_subject"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("last_asked_at").toInstant());
  }

  private static SessionSummary summary(ResultSet rs, int n) throws SQLException {
    return new SessionSummary(
        session(rs, n),
        rs.getString("first_question"),
        rs.getString("first_answer"),
        rs.getInt("question_count"));
  }
}
