package com.pmart.payment.application;

import com.pmart.common.web.PmartException;
import com.pmart.payment.domain.PaymentStage;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.PaymentStore.Cancel;
import com.pmart.payment.infrastructure.toss.TossClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원거래 조회·대사(결제 명세 §6.6·§7.5, 계약 inquiries). 조회는 항상 paymentKey 로(R8). 승인 결과 불명은 직후 1회 → 1분×5 → 상신 →
 * 15분, 취소 불명은 조회해서 없으면 같은 멱등키로 재요청한다. 승인 호출 없이 예약이 만료된 READY 는 EXPIRED 로 정리한다.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final int BATCH = 50;

  private final PaymentStore store;
  private final PaymentLedger ledger;
  private final PaymentEvents events;
  private final CancellationService cancellations;
  private final TossClient toss;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ReconciliationService(
      PaymentStore store,
      PaymentLedger ledger,
      PaymentEvents events,
      CancellationService cancellations,
      TossClient toss,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.ledger = ledger;
    this.events = events;
    this.cancellations = cancellations;
    this.toss = toss;
    this.tx = tx;
    this.clock = clock;
  }

  /** 계약 inquiries: 원거래를 조회해 원장을 대사하고 이력을 남긴다. 같은 requestId 는 기존 이력. */
  public PaymentStore.InquiryRow inquire(String attemptId, String requestId, String reason) {
    var existing = store.inquiry(attemptId, requestId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Attempt a =
        store
            .find(attemptId, false)
            .orElseThrow(() -> PmartException.notFound("PAYMENT_NOT_FOUND", "결제 시도가 없습니다."));
    String tossStatus = null;
    if (a.paymentKey() != null) {
      TossClient.Result r = toss.get(a.paymentKey());
      if (r instanceof TossClient.Result.Unknown u) {
        throw new PmartException(
            "DEPENDENCY_UNAVAILABLE",
            HttpStatus.SERVICE_UNAVAILABLE,
            "토스 원거래를 조회할 수 없습니다(" + u.reason() + ").",
            true,
            List.of());
      }
      tossStatus = applyInquiry(a.id(), r, "inquiry:" + reason);
    }
    Instant now = clock.instant();
    String result = store.find(attemptId, false).orElseThrow().stage().name();
    store.insertInquiry(attemptId, requestId, reason, result, tossStatus, now);
    return store.inquiry(attemptId, requestId).orElseThrow();
  }

  /** 주기 작업: 만료 정리, 불명 승인 재조회, 불명 취소 재조회. */
  @Scheduled(fixedDelayString = "${pmart.payment.reconcile-interval:PT15S}")
  public void run() {
    expireReady();
    for (String id : leaseDue()) {
      try {
        recheck(id);
      } catch (RuntimeException e) {
        log.warn("결제 {} 재조회 실패: {}", id, e.getMessage());
      }
    }
    for (String rid : leaseDueCancels()) {
      try {
        recheckCancel(rid);
      } catch (RuntimeException e) {
        log.warn("취소 {} 재조회 실패: {}", rid, e.getMessage());
      }
    }
  }

  void expireReady() {
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          for (String id : store.lockExpiredReady(now, BATCH)) {
            Attempt a = store.find(id, false).orElseThrow();
            if (store.transition(id, PaymentStage.READY, PaymentStage.EXPIRED, now)) {
              store.recordFailure(id, "EXPIRE", "RESERVATION_EXPIRED", "승인 요청 없이 예약 만료", now);
              store.log(
                  id,
                  "TRANSITION",
                  null,
                  null,
                  PaymentStage.READY,
                  PaymentStage.EXPIRED,
                  "scheduler",
                  null,
                  now);
              events.failed(a, "RESERVATION_EXPIRED", "승인 요청 없이 예약 만료", now);
            }
          }
        });
  }

  private List<String> leaseDue() {
    Instant now = clock.instant();
    List<String> ids = tx.execute(s -> store.leaseDue(now, now.plus(LEASE), BATCH));
    return ids == null ? List.of() : ids;
  }

  private List<String> leaseDueCancels() {
    Instant now = clock.instant();
    List<String> ids = tx.execute(s -> store.leaseDueCancels(now, now.plus(LEASE), BATCH));
    return ids == null ? List.of() : ids;
  }

  /** 불명·상신 승인 1건 재조회(§7.5). */
  void recheck(String attemptId) {
    Attempt a = store.find(attemptId, false).orElseThrow();
    if (a.paymentKey() == null) {
      tx.executeWithoutResult(s -> store.clearSchedule(attemptId, clock.instant()));
      return;
    }
    TossClient.Result r = toss.get(a.paymentKey());
    PaymentLedger.Followup follow =
        r instanceof TossClient.Result.Ok ? null : PaymentLedger.Followup.STILL_UNKNOWN;
    if (follow == null) {
      follow = reconcileTx(attemptId, ((TossClient.Result.Ok) r).payment(), "scheduler");
    }
    if (follow == PaymentLedger.Followup.RECONFIRM) {
      // 인증 완료·승인 전이고 예약이 유효 — 같은 멱등키로 승인 재요청(새 결제 아님)
      Attempt fresh = store.find(attemptId, false).orElseThrow();
      TossClient.Result c =
          toss.confirm(
              fresh.paymentKey(), fresh.id(), fresh.amount(), fresh.confirmIdempotencyKey());
      tx.executeWithoutResult(
          s -> {
            Attempt locked = store.find(attemptId, true).orElseThrow();
            if (c instanceof TossClient.Result.Ok ok) {
              ledger.reconcile(locked, ok.payment(), "scheduler", clock.instant());
              Attempt after = store.find(attemptId, false).orElseThrow();
              if (after.stage() == PaymentStage.CONFIRM_UNKNOWN
                  || after.stage() == PaymentStage.MANUAL_REVIEW) {
                ledger.stillUnknown(after, clock.instant());
              }
            } else if (c instanceof TossClient.Result.Rejected) {
              ledger.applyConfirm(
                  withStage(locked, PaymentStage.CONFIRMING), c, "scheduler", clock.instant());
            } else {
              ledger.stillUnknown(locked, clock.instant());
            }
          });
      return;
    }
    if (follow == PaymentLedger.Followup.STILL_UNKNOWN) {
      tx.executeWithoutResult(
          s -> ledger.stillUnknown(store.find(attemptId, true).orElseThrow(), clock.instant()));
      return;
    }
    // 결과가 정해졌거나(승인·실패·불일치) 승인 뒤 대사(웹훅)였으면 일정을 지운다
    Attempt after = store.find(attemptId, false).orElseThrow();
    if (after.stage() != PaymentStage.CONFIRM_UNKNOWN
        && after.stage() != PaymentStage.MANUAL_REVIEW) {
      tx.executeWithoutResult(s -> store.clearSchedule(attemptId, clock.instant()));
    }
  }

  /** 불명 취소 1건: 조회해서 반영됐으면 끝, 아니면 같은 멱등키로 재요청. */
  void recheckCancel(String requestId) {
    Cancel c = store.cancel(requestId).orElseThrow();
    if (!"UNKNOWN".equals(c.state()) && !"REQUESTED".equals(c.state())) {
      return;
    }
    Attempt a = store.find(c.attemptId(), false).orElseThrow();
    TossClient.Result r = toss.get(java.util.Objects.requireNonNull(a.paymentKey()));
    if (r instanceof TossClient.Result.Ok ok) {
      reconcileTx(a.id(), ok.payment(), "scheduler");
    }
    Cancel after = store.cancel(requestId).orElseThrow();
    if ("UNKNOWN".equals(after.state()) || "REQUESTED".equals(after.state())) {
      cancellations.retry(after);
    }
  }

  private String applyInquiry(String attemptId, TossClient.Result r, String actor) {
    if (r instanceof TossClient.Result.Ok ok) {
      reconcileTx(attemptId, ok.payment(), actor);
      return ok.payment().status();
    }
    if (r instanceof TossClient.Result.Rejected rej) {
      tx.executeWithoutResult(
          s ->
              store.log(
                  attemptId,
                  "INQUIRY",
                  rej.httpStatus(),
                  rej.code(),
                  null,
                  null,
                  actor,
                  null,
                  clock.instant()));
      return "REJECTED:" + rej.code();
    }
    return null;
  }

  private PaymentLedger.Followup reconcileTx(
      String attemptId, TossClient.TossPayment p, String actor) {
    PaymentLedger.Followup f =
        tx.execute(
            s ->
                ledger.reconcile(
                    store.find(attemptId, true).orElseThrow(), p, actor, clock.instant()));
    return f == null ? PaymentLedger.Followup.NONE : f;
  }

  private static Attempt withStage(Attempt a, PaymentStage stage) {
    return new Attempt(
        a.id(),
        a.orderRef(),
        a.draftRef(),
        a.sagaId(),
        a.customerRef(),
        a.sourceSystem(),
        a.entryRequestId(),
        a.amount(),
        a.currency(),
        a.orderName(),
        a.requiresAdult(),
        stage,
        a.paymentKey(),
        a.confirmIdempotencyKey(),
        a.confirmCalledAt(),
        a.resourceExpiresAt(),
        a.priceValidUntil(),
        a.tossStatus(),
        a.method(),
        a.approvedAt(),
        a.totalAmount(),
        a.balanceAmount(),
        a.partialCancelable(),
        a.failStage(),
        a.failCode(),
        a.failMessage(),
        a.mismatchField(),
        a.unknownRetryCount(),
        a.nextCheckAt(),
        a.lastInquiryAt(),
        a.version(),
        a.createdAt());
  }
}
