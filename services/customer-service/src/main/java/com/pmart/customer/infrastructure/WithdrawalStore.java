package com.pmart.customer.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객 탈퇴 표 {@code customer_withdrawal}(V15, ADR-0077)와 완료 때 지우는 개인정보. 다른 서비스 데이터는 건드리지 않는다. */
@Repository
public class WithdrawalStore {

  public static final List<String> OPEN = List.of("PENDING", "BLOCKED_BY_OBLIGATIONS", "UNKNOWN");

  public record WithdrawalRow(
      String withdrawalId,
      String accountId,
      String state,
      @Nullable String reasonCode,
      String openObligationsJson,
      Instant requestedAt,
      @Nullable Instant lastCheckedAt,
      @Nullable Instant completedAt,
      @Nullable Instant revokedAt,
      int version) {}

  private static final String COLS =
      "withdrawal_id, account_id, state, reason_code, open_obligations::text AS obligations,"
          + " requested_at, last_checked_at, completed_at, revoked_at, version";

  private final JdbcTemplate jdbc;

  public WithdrawalStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      String withdrawalId,
      String accountId,
      @Nullable String reasonCode,
      @Nullable String reasonText,
      Instant now) {
    jdbc.update(
        "INSERT INTO customer_withdrawal (withdrawal_id, account_id, state, reason_code, reason_text,"
            + " requested_at, updated_at) VALUES (?, ?, 'PENDING', ?, ?, ?, ?)",
        withdrawalId,
        accountId,
        reasonCode,
        reasonText,
        ts(now),
        ts(now));
  }

  public Optional<WithdrawalRow> find(String withdrawalId) {
    return first(
        "SELECT " + COLS + " FROM customer_withdrawal WHERE withdrawal_id = ?", withdrawalId);
  }

  public Optional<WithdrawalRow> lock(String withdrawalId) {
    return first(
        "SELECT " + COLS + " FROM customer_withdrawal WHERE withdrawal_id = ? FOR UPDATE",
        withdrawalId);
  }

  public List<WithdrawalRow> byAccount(String accountId) {
    return jdbc.query(
        "SELECT "
            + COLS
            + " FROM customer_withdrawal WHERE account_id = ? ORDER BY requested_at DESC LIMIT 20",
        this::row,
        accountId);
  }

  /** 다시 확인할 진행 중 탈퇴 — 마지막 확인이 before 보다 이른 것부터. */
  public List<String> dueForCheck(Instant before, int limit) {
    return jdbc.queryForList(
        "SELECT withdrawal_id FROM customer_withdrawal WHERE state IN ('PENDING', 'BLOCKED_BY_OBLIGATIONS', 'UNKNOWN')"
            + " AND (last_checked_at IS NULL OR last_checked_at < ?) ORDER BY last_checked_at NULLS FIRST LIMIT ?",
        String.class,
        ts(before),
        limit);
  }

  public void markChecked(String withdrawalId, String state, String obligationsJson, Instant now) {
    jdbc.update(
        "UPDATE customer_withdrawal SET state = ?, open_obligations = ?::jsonb, last_checked_at = ?,"
            + " version = version + 1, updated_at = ? WHERE withdrawal_id = ?",
        state,
        obligationsJson,
        ts(now),
        ts(now),
        withdrawalId);
  }

  public void markCompleted(String withdrawalId, Instant now) {
    jdbc.update(
        "UPDATE customer_withdrawal SET state = 'COMPLETED', open_obligations = '[]'::jsonb, last_checked_at = ?,"
            + " completed_at = ?, reason_text = NULL, version = version + 1, updated_at = ? WHERE withdrawal_id = ?",
        ts(now),
        ts(now),
        ts(now),
        withdrawalId);
  }

  public void markRevoked(String withdrawalId, Instant now) {
    jdbc.update(
        "UPDATE customer_withdrawal SET state = 'REVOKED', revoked_at = ?, version = version + 1, updated_at = ?"
            + " WHERE withdrawal_id = ?",
        ts(now),
        ts(now),
        withdrawalId);
  }

  /** 계정 상태(customer.status) 바꾸기 — 버전 +1. */
  public void setAccountStatus(String accountId, String status, Instant now) {
    jdbc.update(
        "UPDATE customer SET status = ?, version = version + 1, updated_at = ? WHERE account_id = ?",
        status,
        ts(now),
        accountId);
  }

  /**
   * 탈퇴 완료 정리(ADR-0077 결정 4) — 이름·이메일·전화·별명·사진 정보, 주소록·쇼핑 맥락·주문 기본값, 휴대폰 확인 시도(번호 원문), 기기 동의 옮김 기록을
   * 지우고 확인 번호를 푼다(released_at — 30일 재가입 막기는 이 값을 본다). 동의 이력·첫 구매 사용 기록·고객번호는 남긴다. 지운 사진 키를 돌려준다(커밋
   * 뒤 S3 에서 지움).
   */
  public @Nullable String erasePersonalData(String accountId, Instant now) {
    String imageKey =
        jdbc.query(
            "SELECT profile_image_key FROM customer WHERE account_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            accountId);
    jdbc.update(
        "UPDATE customer SET status = 'WITHDRAW_COMPLETED', nickname = NULL, name = NULL, provider_nickname = NULL,"
            + " provider_name = NULL, name_source = 'PROVIDER',"
            + " email = NULL,"
            + " email_verified = NULL, phone = NULL, phone_verified = false,"
            + " profile_image_source_url = NULL, profile_image_key = NULL, profile_image_content_type = NULL,"
            + " profile_image_sha256 = NULL, profile_image_copied_from = NULL, profile_image_updated_at = NULL,"
            + " profile_photo_source = 'PROVIDER',"
            + " version = version + 1, updated_at = ? WHERE account_id = ?",
        ts(now),
        accountId);
    jdbc.update(
        "DELETE FROM customer_address WHERE owner_type = 'ACCOUNT' AND owner_id = ?", accountId);
    jdbc.update("DELETE FROM shopping_context WHERE account_id = ?", accountId);
    jdbc.update("DELETE FROM customer_order_preference WHERE account_id = ?", accountId);
    jdbc.update("DELETE FROM phone_verification WHERE account_id = ?", accountId);
    jdbc.update("DELETE FROM customer_device_consent_migration WHERE account_id = ?", accountId);
    jdbc.update(
        "DELETE FROM customer_wishlist_item WHERE account_id = ?", accountId); // 찜(ADR-0081)
    jdbc.update(
        "UPDATE customer_identity SET released_at = ? WHERE account_id = ? AND released_at IS NULL",
        ts(now),
        accountId);
    return imageKey;
  }

  /** 풀린 지 retention 이 지난 확인 번호를 지운다(처리방침 — 탈퇴 후 30일). 지운 행 수. */
  public int purgeReleasedIdentities(Instant releasedBefore) {
    return jdbc.update(
        "DELETE FROM customer_identity WHERE released_at IS NOT NULL AND released_at < ?",
        ts(releasedBefore));
  }

  private Optional<WithdrawalRow> first(String sql, Object... args) {
    return jdbc.query(sql, this::row, args).stream().findFirst();
  }

  private WithdrawalRow row(ResultSet rs, int i) throws SQLException {
    return new WithdrawalRow(
        rs.getString("withdrawal_id"),
        rs.getString("account_id"),
        rs.getString("state"),
        rs.getString("reason_code"),
        rs.getString("obligations"),
        rs.getTimestamp("requested_at").toInstant(),
        instant(rs.getTimestamp("last_checked_at")),
        instant(rs.getTimestamp("completed_at")),
        instant(rs.getTimestamp("revoked_at")),
        rs.getInt("version"));
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
