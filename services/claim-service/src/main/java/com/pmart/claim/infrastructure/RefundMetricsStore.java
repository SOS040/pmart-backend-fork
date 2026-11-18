package com.pmart.claim.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 직원용 기간 환불 집계 SQL(2026-11-18, CL-3). 완료(COMPLETED) 환불 의도를 완료 시각 범위로 근거별 합한다 — 색인
 * ix_refund_intent_completed(V6). 읽기 전용, 개인정보 열 없음.
 */
@Repository
public class RefundMetricsStore {

  /** 근거(refund_intent.reason) 하나의 건수·금액 합. */
  public record ReasonRow(String reason, int count, long amount) {}

  private final JdbcTemplate jdbc;

  public RefundMetricsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<ReasonRow> completedByReason(Instant from, Instant toExclusive) {
    return jdbc.query(
        "SELECT reason, count(*) AS n, coalesce(sum(amount), 0) AS amount FROM refund_intent"
            + " WHERE state = 'COMPLETED' AND completed_at >= ? AND completed_at < ?"
            + " GROUP BY reason",
        (rs, n) -> new ReasonRow(rs.getString("reason"), rs.getInt("n"), rs.getLong("amount")),
        Timestamp.from(from),
        Timestamp.from(toExclusive));
  }
}
