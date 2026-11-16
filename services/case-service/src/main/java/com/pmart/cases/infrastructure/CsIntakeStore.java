package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** CS 접수 저장(V10 {@code cs_intake}, 0.12.0). 요약·메모는 로그에 남기지 않는다. */
@Repository
public class CsIntakeStore {

  public record IntakeRow(
      String intakeId,
      String caseId,
      @Nullable String inquiryId,
      boolean linked,
      String channel,
      String category,
      String customerId,
      @Nullable String subjectRef,
      String summary,
      String callerRelation,
      String identityMethod,
      @Nullable String identityNote,
      @Nullable String consentType,
      @Nullable Boolean consentGiven,
      @Nullable String consentDetail,
      @Nullable Instant consentRecordedAt,
      String receivedBy,
      Instant receivedAt) {}

  private static final String COLS =
      "intake_id, case_id, inquiry_id, linked, channel, category, customer_id, subject_ref,"
          + " summary, caller_relation, identity_method, identity_note, consent_type,"
          + " consent_given, consent_detail, consent_recorded_at, received_by, received_at";

  private final JdbcTemplate jdbc;

  public CsIntakeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(IntakeRow r) {
    jdbc.update(
        "INSERT INTO cs_intake (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.intakeId(),
        r.caseId(),
        r.inquiryId(),
        r.linked(),
        r.channel(),
        r.category(),
        r.customerId(),
        r.subjectRef(),
        r.summary(),
        r.callerRelation(),
        r.identityMethod(),
        r.identityNote(),
        r.consentType(),
        r.consentGiven(),
        r.consentDetail(),
        r.consentRecordedAt() == null ? null : Timestamp.from(r.consentRecordedAt()),
        r.receivedBy(),
        Timestamp.from(r.receivedAt()));
  }

  public Optional<IntakeRow> find(String intakeId) {
    return jdbc
        .query("SELECT " + COLS + " FROM cs_intake WHERE intake_id = ?", this::row, intakeId)
        .stream()
        .findFirst();
  }

  /**
   * 최근 접수 순 한 쪽(조건은 AND). cursor = 앞 쪽 마지막 접수 ID.
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  public List<IntakeRow> page(
      @Nullable String caseId,
      @Nullable String customerId,
      @Nullable String subjectRef,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM cs_intake WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (caseId != null) {
      sql.append(" AND case_id = ?");
      args.add(caseId);
    }
    if (customerId != null) {
      sql.append(" AND customer_id = ?");
      args.add(customerId);
    }
    if (subjectRef != null) {
      sql.append(" AND subject_ref = ?");
      args.add(subjectRef);
    }
    if (cursor != null) {
      sql.append(
          " AND (received_at, intake_id) < (SELECT received_at, intake_id FROM cs_intake"
              + " WHERE intake_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY received_at DESC, intake_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  private IntakeRow row(ResultSet rs, int i) throws SQLException {
    Timestamp consentAt = rs.getTimestamp("consent_recorded_at");
    Object given = rs.getObject("consent_given");
    return new IntakeRow(
        rs.getString("intake_id"),
        rs.getString("case_id"),
        rs.getString("inquiry_id"),
        rs.getBoolean("linked"),
        rs.getString("channel"),
        rs.getString("category"),
        rs.getString("customer_id"),
        rs.getString("subject_ref"),
        rs.getString("summary"),
        rs.getString("caller_relation"),
        rs.getString("identity_method"),
        rs.getString("identity_note"),
        rs.getString("consent_type"),
        given == null ? null : (Boolean) given,
        rs.getString("consent_detail"),
        consentAt == null ? null : consentAt.toInstant(),
        rs.getString("received_by"),
        rs.getTimestamp("received_at").toInstant());
  }
}
