package com.pmart.promotion.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 가입 쿠폰 지급 키·보상 쿠폰 기록(ADR-0082 5단계). */
@Repository
public class CouponGrantStore {

  public record Pending(String orderId, String accountId, String reason) {}

  private final JdbcTemplate jdbc;

  public CouponGrantStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 지금 진행 중인(승인·기간 안·조기 종료 전) 그 발급 방식의 쿠폰 행사 ID — 끝이 가까운 순. */
  public List<String> runningCouponPromotions(String issueMode, Instant at) {
    Timestamp t = Timestamp.from(at);
    return jdbc.queryForList(
        "SELECT promotion_id FROM promotion WHERE kind = 'COUPON' AND issue_mode = ? AND state = 'APPROVED'"
            + " AND starts_at <= ? AND ends_at > ? AND (ended_at IS NULL OR ended_at > ?)"
            + " ORDER BY ends_at, promotion_id",
        String.class,
        issueMode,
        t,
        t,
        t);
  }

  /** 가입 쿠폰 키를 잡는다. 이미 있으면 false(다시 주지 않음). */
  public boolean claimSignup(String signupKey, String accountId, Instant at) {
    return jdbc.update(
            "INSERT INTO signup_grant (signup_key, account_id, granted_at) VALUES (?,?,?)"
                + " ON CONFLICT DO NOTHING",
            signupKey,
            accountId,
            Timestamp.from(at))
        == 1;
  }

  /** 한 장도 못 줬으면 키를 놓는다(같은 트랜잭션 — 다음 가입 행사 때 받을 수 있게). */
  public void releaseSignup(String signupKey) {
    jdbc.update("DELETE FROM signup_grant WHERE signup_key = ?", signupKey);
  }

  /** 보상 기록(주문·사유마다 한 번). 새로 만들었으면 true. */
  public boolean recordCompensation(String orderId, String accountId, String reason, Instant at) {
    return jdbc.update(
            "INSERT INTO compensation_grant (order_id, account_id, reason, state, created_at, updated_at)"
                + " VALUES (?,?,?,'PENDING',?,?) ON CONFLICT DO NOTHING",
            orderId,
            accountId,
            reason,
            Timestamp.from(at),
            Timestamp.from(at))
        == 1;
  }

  /** 아직 못 준 보상(잠금 — 다른 인스턴스는 건너뜀). */
  public List<Pending> lockPending(int limit) {
    return jdbc.query(
        "SELECT order_id, account_id, reason FROM compensation_grant WHERE state = 'PENDING'"
            + " ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3)),
        limit);
  }

  public void markIssued(
      String orderId, String reason, String promotionId, String couponId, Instant at) {
    jdbc.update(
        "UPDATE compensation_grant SET state = 'ISSUED', promotion_id = ?, coupon_id = ?, updated_at = ?"
            + " WHERE order_id = ? AND reason = ? AND state = 'PENDING'",
        promotionId,
        couponId,
        Timestamp.from(at),
        orderId,
        reason);
  }
}
