package com.pmart.claim.application;

import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.FeeRow;
import com.pmart.claim.infrastructure.Upstreams;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 반품 회수비 9,000원 결제(CL-1·D-7 — 고객 사유, 상품 환불과 상계하지 않는 별개 거래). 오배송 다시 배송비(orchestration {@code
 * RedeliveryPaymentService}, ADR-0065)와 같은 결제 경로를 쓴다.
 *
 * <ol>
 *   <li>접수 때 회수비 행(WAITING, 기한 = 접수 + {@code pmart.claim.return-fee-payment-wait}) → 커밋 뒤 payment
 *       결제 시도(주문 참조 {@code RTF-<claimId>}, customerRef = 주문 accountId, 만료 = 기한, 키 {@code
 *       <claimId>:FEE:<n>}) → 첫 시도면 ReturnFeePaymentRequested.
 *   <li>고객 앱 결제(토스 결제위젯 orderId = 결제 시도) → PaymentApproved(order.payment.v1) 또는 재조회 APPROVED → PAID
 *       + 클레임 IN_PROGRESS + 회수 요청.
 *   <li>기한까지 승인이 없으면(진행 중 결제는 기다림) UNPAID + 클레임 CANCELLED(ClaimClosed RETURN_FEE_UNPAID). 기한·취소 뒤
 *       늦은 승인은 보상 취소(RETURN_FEE_TOO_LATE).
 * </ol>
 *
 * 결제 상태 재조회는 sweep(같은 키 재전송·원결과 조회 — 결과 모름을 실패로 단정하지 않는다).
 */
@Service
public class ReturnFeeService {

  private static final Logger log = LoggerFactory.getLogger(ReturnFeeService.class);

  private final ClaimStore store;
  private final Upstreams upstreams;
  private final ClaimEvents events;
  private final ReturnPickups pickups;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final ClaimProperties props;
  private final Clock clock;

  public ReturnFeeService(
      ClaimStore store,
      Upstreams upstreams,
      ClaimEvents events,
      ReturnPickups pickups,
      TransactionTemplate tx,
      ExecutorService refundExecutor,
      ClaimProperties props,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.events = events;
    this.pickups = pickups;
    this.tx = tx;
    this.executor = refundExecutor;
    this.props = props;
    this.clock = clock;
  }

  /** 회수비 행(호출자 트랜잭션 안). 커밋 뒤 결제 시도를 만든다. */
  void open(ClaimRow c, long amount, Instant deadline, Instant now) {
    store.insertFee(
        new FeeRow(
            c.claimId(),
            amount,
            ReturnRules.FEE_WAITING,
            null,
            0,
            null,
            deadline,
            null,
            null,
            now,
            now));
    afterCommit(c.claimId());
  }

  // ---------- 고객 ----------

  /** 지금 쓸 결제 시도(없거나 실패·취소면 새로). 남의 것 404, 결제 대기가 아니거나 기한이 지났으면 409. */
  void retry(String claimId, String accountId) {
    Instant now = clock.instant();
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !accountId.equals(c.accountId()) || !"RETURN".equals(c.type())) {
      throw PmartException.notFound("CLAIM_NOT_FOUND", "클레임이 없습니다.");
    }
    FeeRow f = store.lockFee(claimId).orElse(null);
    if (f == null
        || !ReturnRules.FEE_PENDING.equals(c.state())
        || !ReturnRules.FEE_WAITING.equals(f.state())
        || ReturnRules.expired(f.deadlineAt(), now)) {
      throw PmartException.invalidState("회수비 결제를 기다리는 반품이 아닙니다.");
    }
    if (f.paymentAttemptId() == null) {
      startAttempt(c, f, now, true);
      return;
    }
    String state = upstreams.paymentState(f.paymentAttemptId());
    if ("APPROVED".equals(state)) {
      paid(c, f, f.paymentAttemptId(), now);
      return;
    }
    if (ReturnRules.feeDead(state)) {
      startAttempt(c, f, now, true);
    }
  }

  // ---------- 사건 ----------

  /** order.payment.v1 — 회수비 결제 시도면 깨운다(inbox 트랜잭션 안). 끝난(미결제·취소) 반품에 늦은 승인이면 보상 취소. */
  public void onPaymentEvent(EventEnvelope e) {
    String attemptId = e.payload().path("paymentAttemptId").asString(null);
    if (attemptId == null) {
      return;
    }
    String claimId = store.claimByFeeAttempt(attemptId).orElse(null);
    if (claimId == null) {
      return; // 회수비 결제가 아님(주문 결제·다시 배송비 등)
    }
    if ("PaymentApproved".equals(e.eventType())) {
      approved(claimId, attemptId, clock.instant());
    }
    afterCommit(claimId);
  }

  /** 승인 사실 반영(잠금 아래). WAITING 이면 결제됨, 미결제로 끝난 뒤면 늦은 승인 보상 취소 대기. */
  private void approved(String claimId, String attemptId, Instant now) {
    ClaimRow c = store.lock(claimId).orElse(null);
    FeeRow f = store.lockFee(claimId).orElse(null);
    if (c == null || f == null) {
      return;
    }
    switch (f.state()) {
      case ReturnRules.FEE_WAITING -> paid(c, f, attemptId, now);
      case ReturnRules.FEE_UNPAID -> {
        store.updateFee(
            claimId, ReturnRules.FEE_LATE_CANCEL_PENDING, null, attemptId, null, null, now);
        log.warn("반품 {} 회수비가 기한·취소 뒤 승인됨({}) — 보상 취소", claimId, attemptId);
      }
      default -> {
        if (!attemptId.equals(f.paymentAttemptId())) {
          log.warn(
              "반품 {} 회수비 {} 이미 {} — 다른 시도 {} 승인은 확인 필요",
              claimId,
              f.paymentAttemptId(),
              f.state(),
              attemptId);
        }
      }
    }
  }

  // ---------- 진행 ----------

  private void afterCommit(String claimId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      kick(claimId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            kick(claimId);
          }
        });
  }

  void kick(String claimId) {
    executor.execute(
        () -> {
          try {
            advance(claimId);
          } catch (RuntimeException ex) {
            log.warn("반품 {} 회수비 진행 중 오류 — 재개 대기: {}", claimId, ex.toString());
          }
        });
  }

  @Scheduled(fixedDelayString = "${pmart.claim.refund-sweep-interval:PT15S}")
  public void sweep() {
    Instant now = clock.instant();
    for (String claimId : store.dueFees(now.minus(props.refundRetryAfter()), now)) {
      try {
        advance(claimId);
      } catch (RuntimeException ex) {
        log.warn("반품 {} 회수비 재개 실패: {}", claimId, ex.toString());
      }
    }
  }

  /** 행 잠금 아래에서 한 걸음: 결제 시도 만들기 / 상태 재조회(승인·기한) / 늦은 승인 보상 취소. */
  public void advance(String claimId) {
    tx.executeWithoutResult(
        s -> {
          ClaimRow c = store.lock(claimId).orElse(null);
          FeeRow f = store.lockFee(claimId).orElse(null);
          if (c == null || f == null) {
            return;
          }
          Instant now = clock.instant();
          switch (f.state()) {
            case ReturnRules.FEE_WAITING -> waiting(c, f, now);
            case ReturnRules.FEE_LATE_CANCEL_PENDING -> cancelLate(c, f, now);
            default -> {}
          }
        });
  }

  private void waiting(ClaimRow c, FeeRow f, Instant now) {
    if (!ReturnRules.FEE_PENDING.equals(c.state())) {
      // 상담원이 취소하는 등 결제 대기가 끝났다 — 더 받지 않는다
      store.updateFee(c.claimId(), ReturnRules.FEE_UNPAID, null, null, null, null, now);
      return;
    }
    if (f.paymentAttemptId() == null) {
      if (ReturnRules.expired(f.deadlineAt(), now)) {
        unpaid(c, now);
        return;
      }
      startAttempt(c, f, now, false);
      return;
    }
    String state = upstreams.paymentState(f.paymentAttemptId());
    if ("APPROVED".equals(state)) {
      paid(c, f, f.paymentAttemptId(), now);
      return;
    }
    if (state == null || ReturnRules.feeInFlight(state)) {
      touch(c.claimId(), f, now); // 결과(또는 조회)를 기다린다 — 기한이 지나도
      return;
    }
    if (ReturnRules.expired(f.deadlineAt(), now)) {
      unpaid(c, now);
      return;
    }
    touch(c.claimId(), f, now); // 고객 결제 대기(실패면 고객이 다시 시도)
  }

  private void touch(String claimId, FeeRow f, Instant now) {
    store.updateFee(claimId, f.state(), null, null, null, f.lastError(), now);
  }

  /** 결제 시도 생성(시도 번호마다 키). 업무 거절·모름은 기록만 하고 sweep 이 다시. */
  private void startAttempt(ClaimRow c, FeeRow f, Instant now, boolean customerWaiting) {
    int next = f.attemptNo() + 1;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", ReturnRules.feeChargeRef(c.claimId()));
    body.put("sagaId", c.claimId());
    body.put("amount", f.amount());
    body.put("currency", "KRW");
    body.put("method", "TOSS_PAYMENTS");
    body.put("customerRef", c.accountId());
    body.put("orderName", ReturnRules.FEE_ORDER_NAME);
    body.put("resourceExpiresAt", f.deadlineAt().toString());
    Upstreams.Result res =
        upstreams.startPayment(ReturnRules.feeRequestId(c.claimId(), next), body);
    if (res instanceof Upstreams.Ok ok) {
      String attemptId = ok.body().path("paymentAttemptId").asString("");
      if (attemptId.isBlank()) {
        store.updateFee(c.claimId(), f.state(), null, null, null, "NO_ATTEMPT_ID", now);
        return;
      }
      store.feeAttempt(c.claimId(), attemptId, next, now);
      if (next == 1) {
        events.returnFeePaymentRequested(c, f.amount(), attemptId, f.deadlineAt(), now);
      }
      log.info("반품 {} 회수비 결제 시도 {}({}번째)", c.claimId(), attemptId, next);
      return;
    }
    String code =
        res instanceof Upstreams.Rejected rj
            ? rj.code()
            : res instanceof Upstreams.Unknown u ? u.code() : "UNKNOWN_RESULT";
    store.updateFee(c.claimId(), f.state(), null, null, null, trim(code), now);
    log.warn("반품 {} 회수비 결제 시도 생성 실패 {} — 다시 시도", c.claimId(), code);
    if (customerWaiting && !(res instanceof Upstreams.Rejected)) {
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "결제 서비스에 연결하지 못했습니다. 잠시 뒤 다시 시도하세요.");
    }
    if (customerWaiting) {
      throw new PmartException(
          "PAYMENT_NOT_STARTED",
          HttpStatus.CONFLICT,
          "결제를 시작하지 못했습니다(" + code + ").",
          false,
          List.of());
    }
  }

  /** 결제됨 → 클레임 IN_PROGRESS + 첫 회수 요청. */
  private void paid(ClaimRow c, FeeRow f, String attemptId, Instant now) {
    store.updateFee(c.claimId(), ReturnRules.FEE_PAID, attemptId, null, now, null, now);
    if (!ReturnRules.FEE_PENDING.equals(c.state())) {
      log.warn("반품 {} 회수비 결제됨 — 클레임이 {} 라 회수를 요청하지 않는다", c.claimId(), c.state());
      return;
    }
    store.updateState(c.claimId(), ReturnRules.IN_PROGRESS, null, now, null, now);
    ClaimRow fresh = store.lock(c.claimId()).orElseThrow();
    pickups.request(fresh, "SYSTEM", now);
    log.info("반품 {} 회수비 {}원 결제됨({}) — 회수 요청", c.claimId(), f.amount(), attemptId);
  }

  /** 기한까지 미결제 → 클레임 CANCELLED. */
  private void unpaid(ClaimRow c, Instant now) {
    store.updateFee(c.claimId(), ReturnRules.FEE_UNPAID, null, null, null, null, now);
    store.updateState(c.claimId(), ReturnRules.CANCELLED, "FEE_UNPAID", null, now, now);
    events.claimClosed(c, "RETURN_FEE_UNPAID", now);
    log.info("반품 {} 회수비 기한까지 미결제 — 취소", c.claimId());
  }

  /** 상담원 취소 등으로 결제 대기를 끝낸다(호출자 트랜잭션 안). 그 뒤 승인되면 보상 취소. */
  void stopWaiting(String claimId, Instant now) {
    store
        .lockFee(claimId)
        .filter(f -> ReturnRules.FEE_WAITING.equals(f.state()))
        .ifPresent(
            f -> store.updateFee(claimId, ReturnRules.FEE_UNPAID, null, null, null, null, now));
  }

  private void cancelLate(ClaimRow c, FeeRow f, Instant now) {
    String attempt = f.lateAttemptId();
    if (attempt == null) {
      store.updateFee(c.claimId(), ReturnRules.FEE_LATE_CANCELLED, null, null, null, null, now);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sagaId", c.claimId());
    body.put("step", "RETURN_FEE");
    body.put("reason", "RETURN_FEE_TOO_LATE");
    Upstreams.Result res =
        upstreams.compensationCancel(attempt, c.claimId() + ":FEE-LATE:" + attempt, body);
    switch (res) {
      case Upstreams.Ok ok -> {
        store.updateFee(c.claimId(), ReturnRules.FEE_LATE_CANCELLED, null, null, null, null, now);
        log.info("반품 {} 늦게 승인된 회수비 {} 보상 취소", c.claimId(), attempt);
      }
      case Upstreams.Rejected rj -> {
        store.updateFee(
            c.claimId(),
            ReturnRules.FEE_LATE_CANCEL_FAILED,
            null,
            null,
            null,
            trim(rj.code()),
            now);
        log.error("반품 {} 늦게 승인된 회수비 {} 보상 취소 거절 {} — 운영자 확인", c.claimId(), attempt, rj.code());
      }
      case Upstreams.Unknown u ->
          store.updateFee(c.claimId(), f.state(), null, null, null, trim(u.code()), now);
    }
  }

  private static String trim(String code) {
    return code.length() <= 64 ? code : code.substring(0, 64);
  }
}
