package com.pmart.order.orchestration.infrastructure;

import com.pmart.order.orchestration.domain.CheckoutRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** S1 사가 진행 사본(checkout_saga·saga_step·saga_compensation). JDBC — 행 잠금·조건 갱신을 명시한다. */
@Repository
public class SagaStore {

  public record SagaRow(
      String sagaId,
      String draftId,
      String orderId,
      String purchaseIntentId,
      String originRequestId,
      String customerRef,
      String mfcId,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      long amount,
      @Nullable String orderName,
      boolean containsAlcohol,
      int acceptedQuoteVersion,
      JsonNode lines,
      JsonNode demand,
      String state,
      String currentStep,
      int version,
      Instant quoteExpiresAt,
      @Nullable Instant resourceExpiresAt,
      @Nullable JsonNode payment,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      Instant startedAt,
      Instant updatedAt,
      @Nullable Instant finishedAt) {}

  public record StepRow(
      String step,
      String state,
      @Nullable String targetSystem,
      @Nullable String requestId,
      @Nullable String resultId,
      int attempt,
      @Nullable Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable Instant timeoutAt,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage) {}

  public record CompensationRow(
      String resourceType,
      String resourceId,
      String ownerSystem,
      Instant acquiredAt,
      String releaseState,
      @Nullable String releaseRequestId,
      @Nullable Instant releaseVerifiedAt) {}

  private static final String OPEN =
      "('RUNNING', 'WAITING_RESULT', 'UNKNOWN_TRACKING', 'COMPENSATING')";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public SagaStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(SagaRow s) {
    jdbc.update(
        "INSERT INTO checkout_saga (saga_id, draft_id, order_id, purchase_intent_id, origin_request_id,"
            + " customer_ref, mfc_id, delivery_mode, arrival_date, arrival_start, arrival_end, amount,"
            + " order_name, contains_alcohol, accepted_quote_version, lines, demand, state, current_step,"
            + " version, quote_expires_at, started_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 0, ?, ?, ?)",
        s.sagaId(),
        s.draftId(),
        s.orderId(),
        s.purchaseIntentId(),
        s.originRequestId(),
        s.customerRef(),
        s.mfcId(),
        s.deliveryMode(),
        s.arrivalDate(),
        s.arrivalStart(),
        s.arrivalEnd(),
        s.amount(),
        s.orderName(),
        s.containsAlcohol(),
        s.acceptedQuoteVersion(),
        mapper.writeValueAsString(s.lines()),
        mapper.writeValueAsString(s.demand()),
        s.state(),
        s.currentStep(),
        ts(s.quoteExpiresAt()),
        ts(s.startedAt()),
        ts(s.updatedAt()));
    int seq = 0;
    for (String step : CheckoutRules.STEPS) {
      jdbc.update(
          "INSERT INTO saga_step (saga_id, step, seq, state) VALUES (?, ?, ?, 'PENDING')",
          s.sagaId(),
          step,
          seq++);
    }
  }

  public Optional<SagaRow> find(String sagaId) {
    return jdbc.query("SELECT * FROM checkout_saga WHERE saga_id = ?", this::saga, sagaId).stream()
        .findFirst();
  }

  /** 행 잠금(같은 사가를 두 실행자가 동시에 진행하지 않게). */
  public Optional<SagaRow> lock(String sagaId) {
    return jdbc
        .query("SELECT * FROM checkout_saga WHERE saga_id = ? FOR UPDATE", this::saga, sagaId)
        .stream()
        .findFirst();
  }

  public Optional<SagaRow> activeByDraft(String draftId) {
    return jdbc
        .query(
            "SELECT * FROM checkout_saga WHERE draft_id = ? AND state IN " + OPEN,
            this::saga,
            draftId)
        .stream()
        .findFirst();
  }

  public List<SagaRow> list(
      @Nullable String orderId, @Nullable String draftId, @Nullable String state, int size) {
    StringBuilder sql = new StringBuilder("SELECT * FROM checkout_saga WHERE 1 = 1");
    List<Object> args = new java.util.ArrayList<>();
    if (orderId != null) {
      sql.append(" AND order_id = ?");
      args.add(orderId);
    }
    if (draftId != null) {
      sql.append(" AND draft_id = ?");
      args.add(draftId);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY started_at DESC LIMIT ?");
    args.add(size);
    return jdbc.query(sql.toString(), this::saga, args.toArray());
  }

  /** 재개 대상: 멈춘 RUNNING·COMPENSATING, 자원 만료가 지난 WAITING_RESULT·UNKNOWN_TRACKING. */
  public List<String> due(Instant stuckBefore, Instant now) {
    return jdbc.queryForList(
        "SELECT saga_id FROM checkout_saga WHERE"
            + " (state IN ('RUNNING', 'COMPENSATING') AND updated_at < ?)"
            + " OR (state = 'WAITING_RESULT' AND resource_expires_at < ?)"
            + " OR (state = 'UNKNOWN_TRACKING' AND updated_at < ?)"
            + " ORDER BY updated_at LIMIT 50",
        String.class,
        ts(stuckBefore),
        ts(now),
        ts(stuckBefore));
  }

  public void update(
      String sagaId,
      String state,
      String currentStep,
      @Nullable Instant resourceExpiresAt,
      @Nullable JsonNode payment,
      @Nullable String errorStep,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant now,
      boolean finished) {
    jdbc.update(
        "UPDATE checkout_saga SET state = ?, current_step = ?, resource_expires_at = ?,"
            + " payment = ?::jsonb, last_error_step = ?, last_error_code = ?, last_error_message = ?,"
            + " version = version + 1, updated_at = ?, finished_at = ? WHERE saga_id = ?",
        state,
        currentStep,
        ts(resourceExpiresAt),
        payment == null ? null : mapper.writeValueAsString(payment),
        errorStep,
        errorCode,
        clip(errorMessage),
        ts(now),
        finished ? ts(now) : null,
        sagaId);
  }

  /** 진행 표시만 갱신(멈춤 감시 기준 시각). */
  public void touch(String sagaId, Instant now) {
    jdbc.update("UPDATE checkout_saga SET updated_at = ? WHERE saga_id = ?", ts(now), sagaId);
  }

  // ---------- 단계 ----------

  public List<StepRow> steps(String sagaId) {
    return jdbc.query("SELECT * FROM saga_step WHERE saga_id = ? ORDER BY seq", this::step, sagaId);
  }

  public void stepStarted(
      String sagaId,
      String step,
      String target,
      String requestId,
      Instant now,
      @Nullable Instant timeoutAt) {
    jdbc.update(
        "UPDATE saga_step SET state = 'IN_PROGRESS', target_system = ?, request_id = ?,"
            + " attempt = attempt + 1, started_at = COALESCE(started_at, ?), timeout_at = ?"
            + " WHERE saga_id = ? AND step = ?",
        target,
        requestId,
        ts(now),
        ts(timeoutAt),
        sagaId,
        step);
  }

  public void stepFinished(
      String sagaId,
      String step,
      String state,
      @Nullable String resultId,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant now) {
    jdbc.update(
        "UPDATE saga_step SET state = ?, result_id = COALESCE(?, result_id), last_error_code = ?,"
            + " last_error_message = ?, finished_at = ? WHERE saga_id = ? AND step = ?",
        state,
        resultId,
        errorCode,
        clip(errorMessage),
        "UNKNOWN".equals(state) || "IN_PROGRESS".equals(state) ? null : ts(now),
        sagaId,
        step);
  }

  // ---------- 보상 로그 ----------

  public void acquired(
      String sagaId, String resourceType, String resourceId, String owner, Instant now) {
    jdbc.update(
        "INSERT INTO saga_compensation (saga_id, resource_type, resource_id, owner_system, acquired_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (saga_id, resource_type) DO NOTHING",
        sagaId,
        resourceType,
        resourceId,
        owner,
        ts(now));
  }

  public List<CompensationRow> compensations(String sagaId) {
    return jdbc.query(
        "SELECT * FROM saga_compensation WHERE saga_id = ? ORDER BY acquired_at DESC",
        this::compensation,
        sagaId);
  }

  public void releaseState(
      String sagaId,
      String resourceType,
      String state,
      String requestId,
      @Nullable Instant verifiedAt) {
    jdbc.update(
        "UPDATE saga_compensation SET release_state = ?, release_request_id = ?, release_verified_at = ?"
            + " WHERE saga_id = ? AND resource_type = ?",
        state,
        requestId,
        ts(verifiedAt),
        sagaId,
        resourceType);
  }

  // ---------- 매핑 ----------

  private SagaRow saga(ResultSet rs, int i) throws SQLException {
    java.sql.Date date = rs.getDate("arrival_date");
    String payment = rs.getString("payment");
    return new SagaRow(
        rs.getString("saga_id"),
        rs.getString("draft_id"),
        rs.getString("order_id"),
        rs.getString("purchase_intent_id"),
        rs.getString("origin_request_id"),
        rs.getString("customer_ref"),
        rs.getString("mfc_id"),
        rs.getString("delivery_mode"),
        date == null ? null : date.toLocalDate(),
        rs.getString("arrival_start"),
        rs.getString("arrival_end"),
        rs.getLong("amount"),
        rs.getString("order_name"),
        rs.getBoolean("contains_alcohol"),
        rs.getInt("accepted_quote_version"),
        mapper.readTree(rs.getString("lines")),
        mapper.readTree(rs.getString("demand")),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getInt("version"),
        instant(rs, "quote_expires_at"),
        instant(rs, "resource_expires_at"),
        payment == null ? null : mapper.readTree(payment),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        instant(rs, "started_at"),
        instant(rs, "updated_at"),
        instant(rs, "finished_at"));
  }

  private StepRow step(ResultSet rs, int i) throws SQLException {
    return new StepRow(
        rs.getString("step"),
        rs.getString("state"),
        rs.getString("target_system"),
        rs.getString("request_id"),
        rs.getString("result_id"),
        rs.getInt("attempt"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        instant(rs, "timeout_at"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"));
  }

  private CompensationRow compensation(ResultSet rs, int i) throws SQLException {
    return new CompensationRow(
        rs.getString("resource_type"),
        rs.getString("resource_id"),
        rs.getString("owner_system"),
        instant(rs, "acquired_at"),
        rs.getString("release_state"),
        rs.getString("release_request_id"),
        instant(rs, "release_verified_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable String clip(@Nullable String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
