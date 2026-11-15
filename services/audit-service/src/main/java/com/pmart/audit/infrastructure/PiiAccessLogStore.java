package com.pmart.audit.infrastructure;

import com.pmart.audit.domain.PiiAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 표 {@code pii_access_log}(V2) — 추가와 조회만. UPDATE·DELETE 는 DB 트리거가 거부한다. */
@Repository
public class PiiAccessLogStore {

  /** 저장된 한 건. */
  public record Stored(
      String piiAccessLogId,
      String sourceSystem,
      String requestId,
      Instant recordedAt,
      PiiAccess access,
      long sequence) {}

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public PiiAccessLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(
      String id, String sourceSystem, String requestId, Instant recordedAt, PiiAccess a) {
    jdbc.update(
        "INSERT INTO pii_access_log (pii_access_log_id, source_system, request_id, occurred_at,"
            + " recorded_at, actor_id, actor_type, actor_on_behalf_of, actor_role_grant_ref,"
            + " actor_roles, actor_center_scope, subject_type, subject_ref, access_type, fields,"
            + " purpose, screen, case_ref, record_count, trace_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?,?,?,?,?)",
        id,
        sourceSystem,
        requestId,
        Timestamp.from(a.occurredAt()),
        Timestamp.from(recordedAt),
        a.actorId(),
        a.actorType(),
        a.actorOnBehalfOf(),
        a.actorRoleGrantRef(),
        mapper.writeValueAsString(a.actorRoles()),
        mapper.writeValueAsString(a.actorCenterScope()),
        a.subjectType(),
        a.subjectRef(),
        a.accessType(),
        mapper.writeValueAsString(a.fields()),
        a.purpose(),
        a.screen(),
        a.caseRef(),
        a.recordCount(),
        a.traceId());
  }

  private static final String SELECT =
      "SELECT pii_access_log_id, sequence, source_system, request_id, occurred_at, recorded_at,"
          + " actor_id, actor_type, actor_on_behalf_of, actor_role_grant_ref, actor_roles::text AS"
          + " actor_roles, actor_center_scope::text AS actor_center_scope, subject_type,"
          + " subject_ref, access_type, fields::text AS fields, purpose, screen, case_ref,"
          + " record_count, trace_id FROM pii_access_log";

  public Optional<Stored> find(String id) {
    return jdbc.query(SELECT + " WHERE pii_access_log_id = ?", this::row, id).stream().findFirst();
  }

  /** 직원 조회 조건(0.3.0). 비어 있는 값은 거르지 않는다. */
  public record Search(
      @Nullable String actorId,
      @Nullable String subjectType,
      @Nullable String subjectRef,
      @Nullable String caseRef,
      @Nullable String accessType,
      @Nullable Instant from,
      @Nullable Instant to) {}

  /** 열람 시각 새 것부터, 같은 시각이면 추가 순서 역순. after* = 앞 쪽 마지막 행(keyset). */
  public List<Stored> search(
      Search s, @Nullable Instant afterOccurredAt, long afterSequence, int limit) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
    List<Object> args = new java.util.ArrayList<>();
    eq(sql, args, "actor_id", s.actorId());
    eq(sql, args, "subject_type", s.subjectType());
    eq(sql, args, "subject_ref", s.subjectRef());
    eq(sql, args, "case_ref", s.caseRef());
    eq(sql, args, "access_type", s.accessType());
    if (s.from() != null) {
      sql.append(" AND occurred_at >= ?");
      args.add(Timestamp.from(s.from()));
    }
    if (s.to() != null) {
      sql.append(" AND occurred_at < ?");
      args.add(Timestamp.from(s.to()));
    }
    if (afterOccurredAt != null) {
      sql.append(" AND (occurred_at, sequence) < (?, ?)");
      args.add(Timestamp.from(afterOccurredAt));
      args.add(afterSequence);
    }
    sql.append(" ORDER BY occurred_at DESC, sequence DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  private static void eq(
      StringBuilder sql, List<Object> args, String column, @Nullable String value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = ?");
      args.add(value);
    }
  }

  private Stored row(ResultSet rs, int n) throws SQLException {
    return new Stored(
        rs.getString("pii_access_log_id"),
        rs.getString("source_system"),
        rs.getString("request_id"),
        rs.getTimestamp("recorded_at").toInstant(),
        new PiiAccess(
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("actor_id"),
            rs.getString("actor_type"),
            rs.getString("actor_on_behalf_of"),
            rs.getString("actor_role_grant_ref"),
            strings(rs.getString("actor_roles")),
            strings(rs.getString("actor_center_scope")),
            rs.getString("subject_type"),
            rs.getString("subject_ref"),
            rs.getString("access_type"),
            strings(rs.getString("fields")),
            rs.getString("purpose"),
            rs.getString("screen"),
            rs.getString("case_ref"),
            rs.getInt("record_count"),
            rs.getString("trace_id")),
        rs.getLong("sequence"));
  }

  private List<String> strings(@Nullable String json) {
    return json == null ? List.of() : mapper.readValue(json, STRINGS);
  }

  /** 보존 기간 지난 열람 기록 파기(트랜잭션 안에서 — {@code pmart.audit_purge} 는 그 트랜잭션에만, V3 트리거가 이것만 허용). 지운 수. */
  public int purgeOlderThan(java.time.Instant before, int limit) {
    jdbc.queryForObject("SELECT set_config('pmart.audit_purge', 'on', true)", String.class);
    return jdbc.update(
        "DELETE FROM pii_access_log WHERE pii_access_log_id IN (SELECT pii_access_log_id FROM"
            + " pii_access_log WHERE occurred_at < ? ORDER BY occurred_at LIMIT ?)",
        java.sql.Timestamp.from(before),
        limit);
  }
}
