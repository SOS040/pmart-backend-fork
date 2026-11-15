package com.pmart.order.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 이행 확인 필요(2026-11-08 사용자 결정, 외부 검토 #5): mfc-inventory {@code ReservationBlocked}(격리·실사 손실 등으로 예약
 * 일부가 보류되고 다른 LOT 으로도 못 다시 잡음)를 주문에 표시한다. <b>자동 취소·부분 환불·LOT 대체·고객 안내는 하지 않는다</b>(정책 확정 전) — 주문 상태도
 * 바꾸지 않는다. 담당자(그 센터 운영자)가 목록으로 보고 확인 표시를 남긴다. 실제 결품 처리는 지금처럼 피킹 결품 보고 → 결품 사가.
 */
@Service
public class FulfillmentIssueService {

  public static final String ISSUE_NOT_FOUND = "FULFILLMENT_ISSUE_NOT_FOUND";
  private static final Logger log = LoggerFactory.getLogger(FulfillmentIssueService.class);

  public record Issue(
      String issueId,
      String orderId,
      String mfcId,
      String reservationId,
      String sku,
      @Nullable String lotId,
      int blockedQty,
      String reason,
      String state,
      @Nullable String note,
      @Nullable String acknowledgedBy,
      @Nullable Instant acknowledgedAt,
      Instant blockedAt,
      @Nullable Instant resolvedAt) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public FulfillmentIssueService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** ReservationBlocked → 확인 필요 한 건(같은 사건은 한 번, InboxGuard 트랜잭션 안). */
  public void blocked(EventEnvelope e) {
    JsonNode p = e.payload();
    String orderId = p.path("orderId").asString("");
    if (orderId.isBlank()) {
      log.warn("ReservationBlocked {} 에 주문 ID 가 없어 무시", e.eventId());
      return;
    }
    Instant now = clock.instant();
    String blockedAt = p.path("blockedAt").asString(null);
    int n =
        jdbc.update(
            "INSERT INTO order_fulfillment_issue (issue_id, source_event_id, order_id, mfc_id,"
                + " reservation_id, sku, lot_id, blocked_qty, reason, state, blocked_at, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,'OPEN',?,?) ON CONFLICT (source_event_id) DO NOTHING",
            "FI-" + UUID.randomUUID(),
            String.valueOf(e.eventId()),
            orderId,
            p.path("mfcId").asString(""),
            p.path("reservationId").asString(""),
            p.path("sku").asString(""),
            p.path("lot").asString(null),
            p.path("blockedQty").asInt(0),
            p.path("reason").asString("UNKNOWN"),
            Timestamp.from(blockedAt == null ? now : Instant.parse(blockedAt)),
            Timestamp.from(now));
    if (n > 0) {
      log.info(
          "주문 {} 이행 확인 필요 — {} {}개 보류({})",
          orderId,
          p.path("sku").asString(""),
          p.path("blockedQty").asInt(0),
          p.path("reason").asString(""));
    }
  }

  /**
   * ReservationBlockCleared(피킹 시작 전 재확보, #5 안 C) → 그 예약·SKU 의 남은 보류가 0 이면 열린 확인 필요를 RESOLVED, 아니면
   * 메모만. InboxGuard 트랜잭션 안.
   */
  public void cleared(EventEnvelope e) {
    JsonNode p = e.payload();
    String reservationId = p.path("reservationId").asString("");
    String sku = p.path("sku").asString("");
    int remaining = p.path("remainingBlockedQty").asInt(-1);
    int got = p.path("resecuredQty").asInt(0);
    Instant now = clock.instant();
    String memo = "MFC 재확보 " + got + "개(남은 보류 " + Math.max(remaining, 0) + ") " + now;
    int n;
    if (remaining == 0) {
      n =
          jdbc.update(
              "UPDATE order_fulfillment_issue SET state = 'RESOLVED', resolved_at = ?,"
                  + " note = CASE WHEN note IS NULL THEN ? ELSE note || ' / ' || ? END"
                  + " WHERE reservation_id = ? AND sku = ? AND state IN ('OPEN', 'ACKNOWLEDGED')",
              Timestamp.from(now),
              memo,
              memo,
              reservationId,
              sku);
    } else {
      n =
          jdbc.update(
              "UPDATE order_fulfillment_issue SET"
                  + " note = CASE WHEN note IS NULL THEN ? ELSE note || ' / ' || ? END"
                  + " WHERE reservation_id = ? AND sku = ? AND state IN ('OPEN', 'ACKNOWLEDGED')",
              memo,
              memo,
              reservationId,
              sku);
    }
    if (n > 0) {
      log.info(
          "주문 {} 이행 확인 {}건 — {} ({} {})",
          p.path("orderId").asString(""),
          n,
          remaining == 0 ? "자동 종결" : "메모",
          sku,
          memo);
    }
  }

  @Transactional(readOnly = true)
  public List<Issue> list(String mfcId, @Nullable String state) {
    requireCenter(mfcId);
    return jdbc.query(
        "SELECT * FROM order_fulfillment_issue WHERE mfc_id = ? AND state = ?"
            + " ORDER BY created_at DESC LIMIT 500",
        (rs, i) -> row(rs),
        mfcId,
        state == null ? "OPEN" : state);
  }

  /** 한 주문의 확인 필요 기록(새 것부터). 권한은 호출자({@link StaffOrderService})가 이미 확인했다. */
  List<Issue> forOrder(String orderId) {
    return jdbc.query(
        "SELECT * FROM order_fulfillment_issue WHERE order_id = ? ORDER BY created_at DESC",
        (rs, i) -> row(rs),
        orderId);
  }

  @Transactional
  public Issue acknowledge(String issueId, String note) {
    Issue cur = find(issueId);
    String me = requireCenter(cur.mfcId());
    if ("OPEN".equals(cur.state())) {
      jdbc.update(
          "UPDATE order_fulfillment_issue SET state = 'ACKNOWLEDGED', note = ?, acknowledged_by = ?,"
              + " acknowledged_at = ? WHERE issue_id = ? AND state = 'OPEN'",
          note,
          me,
          Timestamp.from(clock.instant()),
          issueId);
      log.info("이행 확인 {} (주문 {}) by {}: {}", issueId, cur.orderId(), me, note);
    }
    return find(issueId);
  }

  private Issue find(String issueId) {
    return jdbc
        .query(
            "SELECT * FROM order_fulfillment_issue WHERE issue_id = ?", (rs, i) -> row(rs), issueId)
        .stream()
        .findFirst()
        .orElseThrow(() -> PmartException.notFound(ISSUE_NOT_FOUND, "확인 필요 건이 없습니다: " + issueId));
  }

  private static String requireCenter(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p.subject();
  }

  private static Issue row(ResultSet rs) throws SQLException {
    Timestamp ack = rs.getTimestamp("acknowledged_at");
    Timestamp resolved = rs.getTimestamp("resolved_at");
    return new Issue(
        rs.getString("issue_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("reservation_id"),
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getInt("blocked_qty"),
        rs.getString("reason"),
        rs.getString("state"),
        rs.getString("note"),
        rs.getString("acknowledged_by"),
        ack == null ? null : ack.toInstant(),
        rs.getTimestamp("blocked_at").toInstant(),
        resolved == null ? null : resolved.toInstant());
  }
}
