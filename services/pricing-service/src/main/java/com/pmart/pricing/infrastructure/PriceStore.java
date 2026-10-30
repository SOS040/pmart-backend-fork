package com.pmart.pricing.infrastructure;

import com.pmart.pricing.domain.PriceVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 가격 버전·승인 수신·반영 명령·견적 저장(JDBC). */
@Repository
public class PriceStore {

  private static final String VERSION_COLUMNS =
      "sku, version, base_price, previous_price, effective_from, effective_to, state, approval_id";

  /** 신규 버전 입력. */
  public record NewVersion(
      String sku,
      int version,
      long basePrice,
      @Nullable Long previousPrice,
      Instant effectiveFrom,
      @Nullable Instant requestedEffectiveFrom,
      String approvalId,
      @Nullable String changeRequestId,
      @Nullable String approverId,
      @Nullable Integer dossierVersion,
      Instant createdAt) {}

  /** 승인 행 결과. */
  public record ApprovalLineRow(
      String approvalId,
      String sku,
      @Nullable Long basePrice,
      @Nullable Instant requestedEffectiveFrom,
      String state,
      @Nullable String reasonCode,
      @Nullable Integer priceVersion) {}

  public record ApprovalRow(
      String approvalId, @Nullable String changeRequestId, String decision, String result) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public PriceStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  // ---------- 가격 버전 ----------

  /** SKU 의 버전 전체(버전 오름차순). 반영 중에는 {@code forUpdate} 로 SKU 단위 직렬화. */
  public List<PriceVersion> versions(String sku, boolean forUpdate) {
    if (forUpdate) {
      // 첫 버전 경합도 막도록 SKU 키로 트랜잭션 잠금을 건다
      jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, sku);
    }
    return jdbc.query(
        "SELECT " + VERSION_COLUMNS + " FROM price_version WHERE sku = ? ORDER BY version",
        PriceStore::mapVersion,
        sku);
  }

  public Map<String, List<PriceVersion>> versions(Collection<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    return named
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM price_version WHERE sku IN (:skus) ORDER BY sku, version",
            Map.of("skus", skus),
            (rs, i) -> mapVersion(rs, i))
        .stream()
        .collect(Collectors.groupingBy(PriceVersion::sku));
  }

  /** 버전 이력(최신 먼저). {@code beforeVersion} 은 커서. */
  public List<PriceVersion> history(String sku, @Nullable Integer beforeVersion, int limit) {
    return jdbc.query(
        "SELECT "
            + VERSION_COLUMNS
            + " FROM price_version WHERE sku = ? AND (?::int IS NULL OR version < ?::int)"
            + " ORDER BY version DESC LIMIT ?",
        PriceStore::mapVersion,
        sku,
        beforeVersion,
        beforeVersion,
        limit);
  }

  public void insert(NewVersion v) {
    jdbc.update(
        "INSERT INTO price_version (sku, version, base_price, previous_price, effective_from,"
            + " requested_effective_from, state, approval_id, change_request_id, approver_id,"
            + " dossier_version, created_at) VALUES (?,?,?,?,?,?,'SCHEDULED',?,?,?,?,?)",
        v.sku(),
        v.version(),
        v.basePrice(),
        v.previousPrice(),
        Timestamp.from(v.effectiveFrom()),
        ts(v.requestedEffectiveFrom()),
        v.approvalId(),
        v.changeRequestId(),
        v.approverId(),
        v.dossierVersion(),
        Timestamp.from(v.createdAt()));
  }

  /** 효력 시각이 지난 예약 버전(다른 인스턴스가 잡은 행은 건너뜀). */
  public List<PriceVersion> lockDue(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + VERSION_COLUMNS
            + " FROM price_version WHERE state = 'SCHEDULED' AND effective_from <= ?"
            + " ORDER BY effective_from, sku, version LIMIT ? FOR UPDATE SKIP LOCKED",
        PriceStore::mapVersion,
        Timestamp.from(now),
        limit);
  }

  /** 발효: 이 버전 EFFECTIVE, 앞선 EFFECTIVE 는 SUPERSEDED(효력 종료 = 이 버전 효력 시각). */
  public void markEffective(PriceVersion v, Instant publishedAt) {
    jdbc.update(
        "UPDATE price_version SET state = 'SUPERSEDED', effective_to = ?"
            + " WHERE sku = ? AND state = 'EFFECTIVE' AND version < ?",
        Timestamp.from(v.effectiveFrom()),
        v.sku(),
        v.version());
    jdbc.update(
        "UPDATE price_version SET state = 'EFFECTIVE', effective_published_at = ?"
            + " WHERE sku = ? AND version = ? AND state = 'SCHEDULED'",
        Timestamp.from(publishedAt),
        v.sku(),
        v.version());
  }

  // ---------- 승인 수신 ----------

  public boolean approvalReceived(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM price_approval WHERE approval_id = ?", Integer.class, approvalId);
    return n != null && n > 0;
  }

  public Optional<ApprovalRow> approval(String approvalId) {
    return jdbc
        .query(
            "SELECT approval_id, change_request_id, decision, result FROM price_approval"
                + " WHERE approval_id = ?",
            (rs, i) ->
                new ApprovalRow(
                    rs.getString("approval_id"),
                    rs.getString("change_request_id"),
                    rs.getString("decision"),
                    rs.getString("result")),
            approvalId)
        .stream()
        .findFirst();
  }

  public void insertApproval(
      String approvalId,
      @Nullable String changeRequestId,
      String decision,
      String result,
      Instant at) {
    jdbc.update(
        "INSERT INTO price_approval (approval_id, change_request_id, decision, result, received_at)"
            + " VALUES (?,?,?,?,?)",
        approvalId,
        changeRequestId,
        decision,
        result,
        Timestamp.from(at));
  }

  public void insertApprovalLine(ApprovalLineRow l) {
    jdbc.update(
        "INSERT INTO price_approval_line (approval_id, sku, base_price, requested_effective_from,"
            + " state, reason_code, price_version) VALUES (?,?,?,?,?,?,?)",
        l.approvalId(),
        l.sku(),
        l.basePrice(),
        ts(l.requestedEffectiveFrom()),
        l.state(),
        l.reasonCode(),
        l.priceVersion());
  }

  public List<ApprovalLineRow> approvalLines(String approvalId) {
    return jdbc.query(
        "SELECT approval_id, sku, base_price, requested_effective_from, state, reason_code,"
            + " price_version FROM price_approval_line WHERE approval_id = ? ORDER BY sku",
        (rs, i) ->
            new ApprovalLineRow(
                rs.getString("approval_id"),
                rs.getString("sku"),
                rs.getObject("base_price", Long.class),
                instant(rs, "requested_effective_from"),
                rs.getString("state"),
                rs.getString("reason_code"),
                rs.getObject("price_version", Integer.class)),
        approvalId);
  }

  // ---------- 반영 명령 ----------

  public int insertApplication(
      String requestId, String sourceSystem, String approvalId, Instant at) {
    return jdbc.update(
        "INSERT INTO price_version_application (request_id, source_system, approval_id, created_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (request_id) DO NOTHING",
        requestId,
        sourceSystem,
        approvalId,
        Timestamp.from(at));
  }

  public Optional<String> applicationApproval(String requestId) {
    return jdbc
        .queryForList(
            "SELECT approval_id FROM price_version_application WHERE request_id = ?",
            String.class,
            requestId)
        .stream()
        .findFirst();
  }

  // ---------- 견적 ----------

  public void insertQuote(String quoteId, String requestId, String body, Instant at) {
    jdbc.update(
        "INSERT INTO price_quote (quote_id, request_id, body, created_at) VALUES (?,?,?::jsonb,?)",
        quoteId,
        requestId,
        body,
        Timestamp.from(at));
  }

  public Optional<String> quote(String quoteId) {
    return jdbc
        .queryForList(
            "SELECT body::text FROM price_quote WHERE quote_id = ?", String.class, quoteId)
        .stream()
        .findFirst();
  }

  static PriceVersion mapVersion(ResultSet rs, int i) throws SQLException {
    return new PriceVersion(
        rs.getString("sku"),
        rs.getInt("version"),
        rs.getLong("base_price"),
        rs.getObject("previous_price", Long.class),
        rs.getTimestamp("effective_from").toInstant(),
        instant(rs, "effective_to"),
        PriceVersion.State.valueOf(rs.getString("state")),
        rs.getString("approval_id"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
