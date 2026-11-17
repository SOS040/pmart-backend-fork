package com.pmart.claim.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 클레임·클레임 행·환불 의도 원장(JDBC). 반품(RETURN)의 회수비·회수 요청·반영한 MFC 사실·상담원 조치도(V4). */
@Repository
public class ClaimStore {

  /**
   * @param faultCode RETURN — 회사 잘못 사유(있으면 회수비 면제)
   * @param identityMethod RETURN — 상담원 본인 확인 방법(값은 저장하지 않는다)
   * @param createdBy RETURN — 접수 직원
   * @param mfcId RETURN — 반납 센터(원주문 이행 센터)
   * @param pickupState RETURN — 회수 축 상태
   * @param returnDeadlineAt RETURN — 반품 전체 기한(접수 + 설정, 추가 결정 5 — 지나도 회수가 안 되면 자동 취소)
   */
  public record ClaimRow(
      String claimId,
      String orderId,
      String accountId,
      String type,
      String state,
      String reason,
      @Nullable String reasonDetail,
      String channel,
      @Nullable String stopRequestId,
      int version,
      String paymentAttemptId,
      long approvedAmount,
      long itemAmount,
      long bagFee,
      long deliveryTip,
      @Nullable String rejectReason,
      Instant openedAt,
      @Nullable Instant decidedAt,
      @Nullable Instant closedAt,
      Instant updatedAt,
      @Nullable String faultCode,
      @Nullable String identityMethod,
      @Nullable String identityNote,
      @Nullable String caseRef,
      @Nullable String createdBy,
      @Nullable String mfcId,
      @Nullable String pickupState,
      @Nullable Instant returnDeadlineAt) {

    /** 취소 클레임(반품 필드 없음). */
    public ClaimRow(
        String claimId,
        String orderId,
        String accountId,
        String type,
        String state,
        String reason,
        @Nullable String reasonDetail,
        String channel,
        @Nullable String stopRequestId,
        int version,
        String paymentAttemptId,
        long approvedAmount,
        long itemAmount,
        long bagFee,
        long deliveryTip,
        @Nullable String rejectReason,
        Instant openedAt,
        @Nullable Instant decidedAt,
        @Nullable Instant closedAt,
        Instant updatedAt) {
      this(
          claimId,
          orderId,
          accountId,
          type,
          state,
          reason,
          reasonDetail,
          channel,
          stopRequestId,
          version,
          paymentAttemptId,
          approvedAmount,
          itemAmount,
          bagFee,
          deliveryTip,
          rejectReason,
          openedAt,
          decidedAt,
          closedAt,
          updatedAt,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }

  /**
   * @param unitPrice·originalQty·discountShare RETURN — 원주문 스냅샷(환불 근거)
   * @param pickedUpQty·receivedQty·resaleQty·disposeQty RETURN — 회수지 수거·MFC 반입·검수 누계
   */
  public record LineRow(
      String claimLineId,
      String claimId,
      String orderLineId,
      String sku,
      int requestedQty,
      @Nullable Integer approvedQty,
      String decision,
      long lineAmount,
      @Nullable Long unitPrice,
      @Nullable Integer originalQty,
      long discountShare,
      int pickedUpQty,
      int receivedQty,
      int resaleQty,
      int disposeQty) {

    public LineRow(
        String claimLineId,
        String claimId,
        String orderLineId,
        String sku,
        int requestedQty,
        @Nullable Integer approvedQty,
        String decision,
        long lineAmount) {
      this(
          claimLineId,
          claimId,
          orderLineId,
          sku,
          requestedQty,
          approvedQty,
          decision,
          lineAmount,
          null,
          null,
          0,
          0,
          0,
          0,
          0);
    }

    public int inspectedQty() {
      return resaleQty + disposeQty;
    }
  }

  /**
   * @param sourceRef 환불 근거(반품 = 검수 ID). 취소·결품은 없음 — 클레임당 하나.
   */
  public record RefundRow(
      String refundIntentId,
      String claimId,
      String orderId,
      String paymentAttemptId,
      String scope,
      String reason,
      long amount,
      long itemAmount,
      long bagFee,
      long deliveryTip,
      String state,
      String cancellationRequestId,
      @Nullable String pgResult,
      @Nullable String pgTransactionId,
      @Nullable String failureCode,
      int attempts,
      @Nullable Instant requestedAt,
      @Nullable Instant completedAt,
      Instant createdAt,
      Instant updatedAt,
      @Nullable String sourceRef) {

    public RefundRow(
        String refundIntentId,
        String claimId,
        String orderId,
        String paymentAttemptId,
        String scope,
        String reason,
        long amount,
        long itemAmount,
        long bagFee,
        long deliveryTip,
        String state,
        String cancellationRequestId,
        @Nullable String pgResult,
        @Nullable String pgTransactionId,
        @Nullable String failureCode,
        int attempts,
        @Nullable Instant requestedAt,
        @Nullable Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
      this(
          refundIntentId,
          claimId,
          orderId,
          paymentAttemptId,
          scope,
          reason,
          amount,
          itemAmount,
          bagFee,
          deliveryTip,
          state,
          cancellationRequestId,
          pgResult,
          pgTransactionId,
          failureCode,
          attempts,
          requestedAt,
          completedAt,
          createdAt,
          updatedAt,
          null);
    }
  }

  /**
   * 반품 회수비(클레임당 하나).
   *
   * @param refundDecision 반품 취소 때 낸 회수비 결정 REFUND / KEEP(결제된 회수비만, 추가 결정 2)
   * @param refundIntentId 돌려줄 때 환불 의도
   */
  public record FeeRow(
      String claimId,
      long amount,
      String state,
      @Nullable String paymentAttemptId,
      int attemptNo,
      @Nullable String lateAttemptId,
      Instant deadlineAt,
      @Nullable Instant paidAt,
      @Nullable String lastError,
      Instant createdAt,
      Instant updatedAt,
      @Nullable String refundDecision,
      @Nullable String refundIntentId) {

    public FeeRow(
        String claimId,
        long amount,
        String state,
        @Nullable String paymentAttemptId,
        int attemptNo,
        @Nullable String lateAttemptId,
        Instant deadlineAt,
        @Nullable Instant paidAt,
        @Nullable String lastError,
        Instant createdAt,
        Instant updatedAt) {
      this(
          claimId,
          amount,
          state,
          paymentAttemptId,
          attemptNo,
          lateAttemptId,
          deadlineAt,
          paidAt,
          lastError,
          createdAt,
          updatedAt,
          null,
          null);
    }
  }

  /**
   * 반품 회수 요청(시도마다 하나).
   *
   * @param failureKind 실패 종류 CUSTOMER / OURS(횟수에서 뺌) / OTHER — 실패한 요청만
   */
  public record PickupRow(
      String pickupRequestId,
      String claimId,
      int attemptNo,
      @Nullable String deliveryId,
      String state,
      @Nullable String failureReason,
      String requestedBy,
      Instant requestedAt,
      Instant updatedAt,
      @Nullable String failureKind) {

    public PickupRow(
        String pickupRequestId,
        String claimId,
        int attemptNo,
        @Nullable String deliveryId,
        String state,
        @Nullable String failureReason,
        String requestedBy,
        Instant requestedAt,
        Instant updatedAt) {
      this(
          pickupRequestId,
          claimId,
          attemptNo,
          deliveryId,
          state,
          failureReason,
          requestedBy,
          requestedAt,
          updatedAt,
          null);
    }

    public boolean failed() {
      return "FAILED".equals(state);
    }
  }

  private final JdbcTemplate jdbc;

  public ClaimStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 클레임 ----------

  public void insert(ClaimRow c, List<LineRow> lines) {
    jdbc.update(
        "INSERT INTO claim (claim_id, order_id, account_id, type, state, reason, reason_detail,"
            + " channel, stop_request_id, version, payment_attempt_id, approved_amount, item_amount,"
            + " bag_fee, delivery_tip, opened_at, decided_at, closed_at, updated_at, fault_code,"
            + " identity_method, identity_note, case_ref, created_by, mfc_id, pickup_state,"
            + " return_deadline_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        c.claimId(),
        c.orderId(),
        c.accountId(),
        c.type(),
        c.state(),
        c.reason(),
        c.reasonDetail(),
        c.channel(),
        c.stopRequestId(),
        c.version(),
        c.paymentAttemptId(),
        c.approvedAmount(),
        c.itemAmount(),
        c.bagFee(),
        c.deliveryTip(),
        ts(c.openedAt()),
        ts(c.decidedAt()),
        ts(c.closedAt()),
        ts(c.updatedAt()),
        c.faultCode(),
        c.identityMethod(),
        c.identityNote(),
        c.caseRef(),
        c.createdBy(),
        c.mfcId(),
        c.pickupState(),
        ts(c.returnDeadlineAt()));
    for (LineRow l : lines) {
      jdbc.update(
          "INSERT INTO claim_line (claim_line_id, claim_id, order_line_id, sku, requested_qty,"
              + " approved_qty, decision, line_amount, unit_price, original_qty, discount_share)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          l.claimLineId(),
          l.claimId(),
          l.orderLineId(),
          l.sku(),
          l.requestedQty(),
          l.approvedQty(),
          l.decision(),
          l.lineAmount(),
          l.unitPrice(),
          l.originalQty(),
          l.discountShare());
    }
  }

  public Optional<ClaimRow> find(String claimId) {
    return one("SELECT * FROM claim WHERE claim_id = ?", claimId);
  }

  public Optional<ClaimRow> lock(String claimId) {
    return one("SELECT * FROM claim WHERE claim_id = ? FOR UPDATE", claimId);
  }

  public Optional<ClaimRow> lockByStopRequest(String stopRequestId) {
    return one("SELECT * FROM claim WHERE stop_request_id = ? FOR UPDATE", stopRequestId);
  }

  /** 이미 결품 환불(OUT_OF_STOCK, 승인 행)한 수량 — 같은 결품을 두 번 환불하지 않게. */
  public int shortageRefundedQty(String orderId, String orderLineId) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(l.approved_qty), 0) FROM claim_line l JOIN claim c ON c.claim_id = l.claim_id"
                + " WHERE c.order_id = ? AND c.reason = 'OUT_OF_STOCK' AND l.order_line_id = ?"
                + " AND l.decision = 'APPROVED'",
            Integer.class,
            orderId,
            orderLineId);
    return v == null ? 0 : v;
  }

  /** 진행 중인 전체 취소(현장 중지가 있는 것). 결품 부분 환불 클레임(중지 없음)은 아니다. */
  public Optional<ClaimRow> activeCancel(String orderId) {
    return one(
        "SELECT * FROM claim WHERE order_id = ? AND type = 'CANCEL' AND stop_request_id IS NOT NULL"
            + " AND state NOT IN ('CLOSED', 'REJECTED', 'WITHDRAWN')",
        orderId);
  }

  /**
   * 이 주문에 이미 나간(실패 아닌) 환불 합 — [금액, 상품 금액]. 전체 취소 환불에서 뺀다(결품 부분 환불 뒤 전체 취소). 반품 회수비 돌려주기(RETURN_FEE
   * — 원주문 결제가 아니라 회수비 결제의 취소)는 빼고.
   */
  public long[] refundedForOrder(String orderId) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) AS a, COALESCE(SUM(item_amount), 0) AS i FROM refund_intent"
            + " WHERE order_id = ? AND state <> 'FAILED' AND reason <> 'RETURN_FEE'",
        (rs, n) -> new long[] {rs.getLong("a"), rs.getLong("i")},
        orderId);
  }

  /** 목록(최근 접수 순). accountId 가 있으면 그 고객 것만. */
  public List<ClaimRow> list(
      @Nullable String accountId,
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String state,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM claim WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (accountId != null) {
      sql.append(" AND account_id = ?");
      args.add(accountId);
    }
    if (orderId != null) {
      sql.append(" AND order_id = ?");
      args.add(orderId);
    }
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY opened_at DESC, claim_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> claim(rs), args.toArray());
  }

  public List<LineRow> lines(String claimId) {
    return jdbc.query(
        "SELECT * FROM claim_line WHERE claim_id = ? ORDER BY claim_line_id",
        (rs, i) -> line(rs),
        claimId);
  }

  public void updateState(
      String claimId,
      String state,
      @Nullable String rejectReason,
      @Nullable Instant decidedAt,
      @Nullable Instant closedAt,
      Instant now) {
    jdbc.update(
        "UPDATE claim SET state = ?, reject_reason = COALESCE(?, reject_reason),"
            + " decided_at = COALESCE(?, decided_at), closed_at = COALESCE(?, closed_at),"
            + " version = version + 1, updated_at = ? WHERE claim_id = ?",
        state,
        rejectReason,
        ts(decidedAt),
        ts(closedAt),
        ts(now),
        claimId);
  }

  /** 행 결정(출고 전 취소 확정 수량). */
  public void decideLine(String claimId, String orderLineId, int approvedQty, String decision) {
    jdbc.update(
        "UPDATE claim_line SET approved_qty = ?, decision = ? WHERE claim_id = ? AND order_line_id = ?",
        approvedQty,
        decision,
        claimId,
        orderLineId);
  }

  // ---------- 반품(RETURN, V4) ----------

  /** 이 주문의 진행·완료 반품 수량(행별) — 취소·거절·철회된 반품은 빼고. 반품 가능 수량 = 인계 − 이 값. */
  public Map<String, Integer> returnOccupied(String orderId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT l.order_line_id, COALESCE(SUM(l.requested_qty), 0) AS q FROM claim_line l"
            + " JOIN claim c ON c.claim_id = l.claim_id WHERE c.order_id = ? AND c.type = 'RETURN'"
            + " AND c.state NOT IN ('CANCELLED', 'REJECTED', 'WITHDRAWN') GROUP BY l.order_line_id",
        rs -> {
          out.put(rs.getString("order_line_id"), rs.getInt("q"));
        },
        orderId);
    return out;
  }

  /** 같은 주문의 반품 접수를 줄 세운다(반품 가능 수량 경합 — 트랜잭션 끝까지). */
  public void lockOrderForReturn(String orderId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "claim-return:" + orderId);
  }

  public void updatePickupState(String claimId, String pickupState, Instant now) {
    jdbc.update(
        "UPDATE claim SET pickup_state = ?, updated_at = ? WHERE claim_id = ?",
        pickupState,
        ts(now),
        claimId);
  }

  /** 회수·반입·검수 누계를 더한다(요청 수량을 넘지 않게 호출자가 배분). */
  public void addLineProgress(
      String claimLineId, int pickedUp, int received, int resale, int dispose) {
    jdbc.update(
        "UPDATE claim_line SET picked_up_qty = picked_up_qty + ?, received_qty = received_qty + ?,"
            + " resale_qty = resale_qty + ?, dispose_qty = dispose_qty + ? WHERE claim_line_id = ?",
        pickedUp,
        received,
        resale,
        dispose,
        claimLineId);
  }

  /** 정산 수량(반품 — "받은 만큼만 끝내기" 때 받은 수량으로 줄인다, 추가 결정 6). */
  public void settleLine(String claimLineId, int approvedQty) {
    jdbc.update(
        "UPDATE claim_line SET approved_qty = ? WHERE claim_line_id = ?", approvedQty, claimLineId);
  }

  /**
   * 반품 전체 기한이 지났는데 회수가 하나도 안 된 반품(추가 결정 5) — 진행 중인 회수 요청(REQUESTED)이 있거나 무엇이든 수거·반입·검수됐으면 빼고(앞은 결과를
   * 기다리고, 뒤는 상담원 "받은 만큼만 끝내기").
   */
  public List<String> overdueReturns(Instant now) {
    return jdbc.queryForList(
        "SELECT c.claim_id FROM claim c WHERE c.type = 'RETURN'"
            + " AND c.state IN ('FEE_PENDING', 'IN_PROGRESS', 'RECOVERY_FAILED')"
            + " AND c.return_deadline_at <= ?"
            + " AND NOT EXISTS (SELECT 1 FROM return_pickup p WHERE p.claim_id = c.claim_id"
            + " AND p.state IN ('REQUESTED', 'PICKED_UP', 'DELIVERED'))"
            + " AND NOT EXISTS (SELECT 1 FROM claim_line l WHERE l.claim_id = c.claim_id"
            + " AND (l.picked_up_qty > 0 OR l.received_qty > 0 OR l.resale_qty + l.dispose_qty > 0))"
            + " ORDER BY c.return_deadline_at LIMIT 50",
        String.class,
        ts(now));
  }

  /** 반영한 MFC 사실 기록. 이미 있으면 false(같은 반입·검수를 두 번 세지 않는다). */
  public boolean markFact(String kind, String ref, String claimId, Instant now) {
    return jdbc.update(
            "INSERT INTO return_fact (kind, ref, claim_id, applied_at) VALUES (?,?,?,?)"
                + " ON CONFLICT (kind, ref) DO NOTHING",
            kind,
            ref,
            claimId,
            ts(now))
        == 1;
  }

  public void insertAction(
      String actionId, String claimId, String action, String actor, String note, Instant now) {
    jdbc.update(
        "INSERT INTO claim_action (action_id, claim_id, action, actor, note, acted_at)"
            + " VALUES (?,?,?,?,?,?)",
        actionId,
        claimId,
        action,
        actor,
        note,
        ts(now));
  }

  // 회수비

  public void insertFee(FeeRow f) {
    jdbc.update(
        "INSERT INTO return_fee (claim_id, amount, state, payment_attempt_id, attempt_no,"
            + " deadline_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        f.claimId(),
        f.amount(),
        f.state(),
        f.paymentAttemptId(),
        f.attemptNo(),
        ts(f.deadlineAt()),
        ts(f.createdAt()),
        ts(f.updatedAt()));
  }

  public Optional<FeeRow> fee(String claimId) {
    return jdbc
        .query("SELECT * FROM return_fee WHERE claim_id = ?", (rs, i) -> fee(rs), claimId)
        .stream()
        .findFirst();
  }

  public Optional<FeeRow> lockFee(String claimId) {
    return jdbc
        .query(
            "SELECT * FROM return_fee WHERE claim_id = ? FOR UPDATE", (rs, i) -> fee(rs), claimId)
        .stream()
        .findFirst();
  }

  /** 결제 시도 → 클레임(지금 시도·지난 시도 모두). */
  public Optional<String> claimByFeeAttempt(String paymentAttemptId) {
    return jdbc
        .queryForList(
            "SELECT claim_id FROM return_fee_attempt WHERE payment_attempt_id = ?",
            String.class,
            paymentAttemptId)
        .stream()
        .findFirst();
  }

  public void feeAttempt(String claimId, String paymentAttemptId, int attemptNo, Instant now) {
    jdbc.update(
        "INSERT INTO return_fee_attempt (payment_attempt_id, claim_id, attempt_no, created_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (payment_attempt_id) DO NOTHING",
        paymentAttemptId,
        claimId,
        attemptNo,
        ts(now));
    jdbc.update(
        "UPDATE return_fee SET payment_attempt_id = ?, attempt_no = ?, last_error = NULL,"
            + " updated_at = ? WHERE claim_id = ?",
        paymentAttemptId,
        attemptNo,
        ts(now),
        claimId);
  }

  public void updateFee(
      String claimId,
      String state,
      @Nullable String paymentAttemptId,
      @Nullable String lateAttemptId,
      @Nullable Instant paidAt,
      @Nullable String lastError,
      Instant now) {
    jdbc.update(
        "UPDATE return_fee SET state = ?, payment_attempt_id = COALESCE(?, payment_attempt_id),"
            + " late_attempt_id = COALESCE(?, late_attempt_id), paid_at = COALESCE(?, paid_at),"
            + " last_error = ?, updated_at = ? WHERE claim_id = ?",
        state,
        paymentAttemptId,
        lateAttemptId,
        ts(paidAt),
        lastError,
        ts(now),
        claimId);
  }

  /** 반품 취소 때 낸 회수비 결정(REFUND·KEEP)과 돌려줄 때 환불 의도. */
  public void decideFeeRefund(
      String claimId, String decision, @Nullable String refundIntentId, Instant now) {
    jdbc.update(
        "UPDATE return_fee SET refund_decision = ?, refund_intent_id = ?, updated_at = ?"
            + " WHERE claim_id = ?",
        decision,
        refundIntentId,
        ts(now),
        claimId);
  }

  /** 재조회 대상 — 결제 대기(오래됐거나 기한이 지난 것)·늦은 승인 보상 취소 대기. */
  public List<String> dueFees(Instant staleBefore, Instant now) {
    return jdbc.queryForList(
        "SELECT claim_id FROM return_fee WHERE state IN ('WAITING', 'LATE_CANCEL_PENDING')"
            + " AND (updated_at < ? OR deadline_at <= ?) ORDER BY updated_at LIMIT 50",
        String.class,
        ts(staleBefore),
        ts(now));
  }

  // 회수 요청

  public void insertPickup(PickupRow p) {
    jdbc.update(
        "INSERT INTO return_pickup (pickup_request_id, claim_id, attempt_no, delivery_id, state,"
            + " requested_by, requested_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        p.pickupRequestId(),
        p.claimId(),
        p.attemptNo(),
        p.deliveryId(),
        p.state(),
        p.requestedBy(),
        ts(p.requestedAt()),
        ts(p.updatedAt()));
  }

  public List<PickupRow> pickups(String claimId) {
    return jdbc.query(
        "SELECT * FROM return_pickup WHERE claim_id = ? ORDER BY attempt_no",
        (rs, i) -> pickup(rs),
        claimId);
  }

  public Optional<PickupRow> pickupByDelivery(String deliveryId) {
    return jdbc
        .query(
            "SELECT * FROM return_pickup WHERE delivery_id = ? FOR UPDATE",
            (rs, i) -> pickup(rs),
            deliveryId)
        .stream()
        .findFirst();
  }

  public Optional<PickupRow> pickup(String pickupRequestId) {
    return jdbc
        .query(
            "SELECT * FROM return_pickup WHERE pickup_request_id = ? FOR UPDATE",
            (rs, i) -> pickup(rs),
            pickupRequestId)
        .stream()
        .findFirst();
  }

  /** 클레임의 마지막 회수 요청. */
  public Optional<PickupRow> lastPickup(String claimId) {
    return jdbc
        .query(
            "SELECT * FROM return_pickup WHERE claim_id = ? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
            (rs, i) -> pickup(rs),
            claimId)
        .stream()
        .findFirst();
  }

  public void linkPickupDelivery(String pickupRequestId, String deliveryId, Instant now) {
    jdbc.update(
        "UPDATE return_pickup SET delivery_id = ?, updated_at = ? WHERE pickup_request_id = ?"
            + " AND delivery_id IS NULL",
        deliveryId,
        ts(now),
        pickupRequestId);
  }

  public void updatePickup(
      String pickupRequestId,
      String state,
      @Nullable String deliveryId,
      @Nullable String failureReason,
      Instant now) {
    jdbc.update(
        "UPDATE return_pickup SET state = ?, delivery_id = COALESCE(delivery_id, ?),"
            + " failure_reason = COALESCE(?, failure_reason), updated_at = ? WHERE pickup_request_id = ?",
        state,
        deliveryId,
        failureReason,
        ts(now),
        pickupRequestId);
  }

  /** 회수 요청 실패(사유·종류). */
  public void failPickup(
      String pickupRequestId,
      @Nullable String deliveryId,
      @Nullable String failureReason,
      String failureKind,
      Instant now) {
    jdbc.update(
        "UPDATE return_pickup SET state = 'FAILED', delivery_id = COALESCE(delivery_id, ?),"
            + " failure_reason = COALESCE(?, failure_reason), failure_kind = ?, updated_at = ?"
            + " WHERE pickup_request_id = ?",
        deliveryId,
        failureReason,
        failureKind,
        ts(now),
        pickupRequestId);
  }

  // ---------- 환불 의도 ----------

  public void insertRefund(RefundRow r) {
    jdbc.update(
        "INSERT INTO refund_intent (refund_intent_id, claim_id, order_id, payment_attempt_id, scope,"
            + " reason, amount, item_amount, bag_fee, delivery_tip, state, cancellation_request_id,"
            + " attempts, created_at, updated_at, source_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
        r.refundIntentId(),
        r.claimId(),
        r.orderId(),
        r.paymentAttemptId(),
        r.scope(),
        r.reason(),
        r.amount(),
        r.itemAmount(),
        r.bagFee(),
        r.deliveryTip(),
        r.state(),
        r.cancellationRequestId(),
        ts(r.createdAt()),
        ts(r.updatedAt()),
        r.sourceRef());
  }

  public List<RefundRow> refunds(String claimId) {
    return jdbc.query(
        "SELECT * FROM refund_intent WHERE claim_id = ? ORDER BY created_at, refund_intent_id",
        (rs, i) -> refund(rs),
        claimId);
  }

  public Optional<RefundRow> lockRefund(String refundIntentId) {
    return jdbc
        .query(
            "SELECT * FROM refund_intent WHERE refund_intent_id = ? FOR UPDATE",
            (rs, i) -> refund(rs),
            refundIntentId)
        .stream()
        .findFirst();
  }

  /** 실행 대기·PG 결과 대기·불명이 오래된 환불(재실행·원결과 조회 대상). */
  public List<String> dueRefunds(Instant before) {
    return jdbc.queryForList(
        "SELECT refund_intent_id FROM refund_intent"
            + " WHERE state IN ('PENDING_EXECUTION', 'PG_PENDING', 'UNKNOWN') AND updated_at < ?"
            + " ORDER BY updated_at LIMIT 50",
        String.class,
        ts(before));
  }

  public void updateRefund(
      String refundIntentId,
      String state,
      @Nullable String pgResult,
      @Nullable String pgTransactionId,
      @Nullable String failureCode,
      boolean attempted,
      @Nullable Instant completedAt,
      Instant now) {
    jdbc.update(
        "UPDATE refund_intent SET state = ?, pg_result = COALESCE(?, pg_result),"
            + " pg_transaction_id = COALESCE(?, pg_transaction_id), failure_code = ?,"
            + " attempts = attempts + ?, requested_at = CASE WHEN ? THEN COALESCE(requested_at, ?)"
            + " ELSE requested_at END, completed_at = COALESCE(?, completed_at), updated_at = ?"
            + " WHERE refund_intent_id = ?",
        state,
        pgResult,
        pgTransactionId,
        failureCode,
        attempted ? 1 : 0,
        attempted,
        ts(now),
        ts(completedAt),
        ts(now),
        refundIntentId);
  }

  // ---------- 공통 ----------

  private Optional<ClaimRow> one(String sql, Object arg) {
    return jdbc.query(sql, (rs, i) -> claim(rs), arg).stream().findFirst();
  }

  private static ClaimRow claim(ResultSet rs) throws SQLException {
    return new ClaimRow(
        rs.getString("claim_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("type"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("reason_detail"),
        rs.getString("channel"),
        rs.getString("stop_request_id"),
        rs.getInt("version"),
        rs.getString("payment_attempt_id"),
        rs.getLong("approved_amount"),
        rs.getLong("item_amount"),
        rs.getLong("bag_fee"),
        rs.getLong("delivery_tip"),
        rs.getString("reject_reason"),
        rs.getTimestamp("opened_at").toInstant(),
        instant(rs.getTimestamp("decided_at")),
        instant(rs.getTimestamp("closed_at")),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("fault_code"),
        rs.getString("identity_method"),
        rs.getString("identity_note"),
        rs.getString("case_ref"),
        rs.getString("created_by"),
        rs.getString("mfc_id"),
        rs.getString("pickup_state"),
        instant(rs.getTimestamp("return_deadline_at")));
  }

  private static LineRow line(ResultSet rs) throws SQLException {
    return new LineRow(
        rs.getString("claim_line_id"),
        rs.getString("claim_id"),
        rs.getString("order_line_id"),
        rs.getString("sku"),
        rs.getInt("requested_qty"),
        (Integer) rs.getObject("approved_qty"),
        rs.getString("decision"),
        rs.getLong("line_amount"),
        (Long) rs.getObject("unit_price"),
        (Integer) rs.getObject("original_qty"),
        rs.getLong("discount_share"),
        rs.getInt("picked_up_qty"),
        rs.getInt("received_qty"),
        rs.getInt("resale_qty"),
        rs.getInt("dispose_qty"));
  }

  private static RefundRow refund(ResultSet rs) throws SQLException {
    return new RefundRow(
        rs.getString("refund_intent_id"),
        rs.getString("claim_id"),
        rs.getString("order_id"),
        rs.getString("payment_attempt_id"),
        rs.getString("scope"),
        rs.getString("reason"),
        rs.getLong("amount"),
        rs.getLong("item_amount"),
        rs.getLong("bag_fee"),
        rs.getLong("delivery_tip"),
        rs.getString("state"),
        rs.getString("cancellation_request_id"),
        rs.getString("pg_result"),
        rs.getString("pg_transaction_id"),
        rs.getString("failure_code"),
        rs.getInt("attempts"),
        instant(rs.getTimestamp("requested_at")),
        instant(rs.getTimestamp("completed_at")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("source_ref"));
  }

  private static FeeRow fee(ResultSet rs) throws SQLException {
    return new FeeRow(
        rs.getString("claim_id"),
        rs.getLong("amount"),
        rs.getString("state"),
        rs.getString("payment_attempt_id"),
        rs.getInt("attempt_no"),
        rs.getString("late_attempt_id"),
        rs.getTimestamp("deadline_at").toInstant(),
        instant(rs.getTimestamp("paid_at")),
        rs.getString("last_error"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("refund_decision"),
        rs.getString("refund_intent_id"));
  }

  private static PickupRow pickup(ResultSet rs) throws SQLException {
    return new PickupRow(
        rs.getString("pickup_request_id"),
        rs.getString("claim_id"),
        rs.getInt("attempt_no"),
        rs.getString("delivery_id"),
        rs.getString("state"),
        rs.getString("failure_reason"),
        rs.getString("requested_by"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("failure_kind"));
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
