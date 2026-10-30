package com.pmart.payment.application;

import com.pmart.payment.domain.PaymentRules;
import com.pmart.payment.domain.PaymentStage;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.PaymentStore.Cancel;
import com.pmart.payment.infrastructure.toss.TossClient.Result;
import com.pmart.payment.infrastructure.toss.TossClient.TossCancel;
import com.pmart.payment.infrastructure.toss.TossClient.TossPayment;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 토스 결과를 결제 시도·취소 원장에 반영한다(결제 명세 §5·§6.3-9·§6.5-6·§7.5). 호출자 트랜잭션 안에서, 결제 시도 행을 잠근 뒤 쓴다. 승인은
 * status=DONE 과 R3 대조를 모두 통과할 때만이다. 시간 경과만으로 실패 처리하지 않는다.
 */
@Component
public class PaymentLedger {

  private static final Logger log = LoggerFactory.getLogger(PaymentLedger.class);

  /** 원거래 조회 뒤 다음 할 일. */
  public enum Followup {
    NONE,
    /** 토스가 IN_PROGRESS(인증 완료·승인 전)이고 예약이 유효 — 같은 멱등키로 승인 재요청 */
    RECONFIRM,
    /** 아직 모름 — 재조회 일정 */
    STILL_UNKNOWN
  }

  private final PaymentStore store;
  private final PaymentEvents events;

  public PaymentLedger(PaymentStore store, PaymentEvents events) {
    this.store = store;
    this.events = events;
  }

  /** 승인 API 응답 반영(§6.3-9). */
  public void applyConfirm(Attempt a, Result r, String actor, Instant now) {
    switch (r) {
      case Result.Ok ok -> {
        store.log(
            a.id(),
            "CONFIRM",
            ok.httpStatus(),
            ok.payment().status(),
            a.stage(),
            null,
            actor,
            null,
            now);
        settleConfirmed(a, ok.payment(), true, actor, now);
      }
      case Result.Rejected rej -> {
        store.log(
            a.id(),
            "CONFIRM",
            rej.httpStatus(),
            rej.code(),
            a.stage(),
            PaymentStage.CONFIRM_FAILED,
            actor,
            null,
            now);
        fail(a, PaymentStage.CONFIRM_FAILED, "CONFIRM", rej.code(), rej.message(), now);
      }
      case Result.Unknown u -> {
        store.log(
            a.id(),
            "CONFIRM",
            u.httpStatus(),
            u.reason(),
            a.stage(),
            PaymentStage.CONFIRM_UNKNOWN,
            actor,
            null,
            now);
        store.setStage(a.id(), PaymentStage.CONFIRM_UNKNOWN, now);
        // 직후 1회 재조회(§7.5)
        store.schedule(a.id(), 0, now, now);
        events.unknown(a, 0, now, false);
      }
    }
  }

  /**
   * 원거래 조회 결과 반영(대사). 승인 단계가 결정되지 않았던 시도(CONFIRMING·불명·상신)는 여기서 결정되고, 승인 뒤 시도는 토스 잔액·취소 결과를 맞춘다.
   */
  public Followup reconcile(Attempt a, TossPayment p, String actor, Instant now) {
    store.log(a.id(), "INQUIRY", 200, p.status(), a.stage(), null, actor, null, now);
    Optional<String> idMismatch =
        PaymentRules.mismatch(
            a.id(), a.paymentKey(), a.amount(), p.orderId(), p.paymentKey(), a.amount(), "KRW");
    if (idMismatch.isPresent()) {
      hold(a, p, idMismatch.get(), actor, now);
      return Followup.NONE;
    }
    String status = p.status() == null ? "" : p.status();
    if (pending(a.stage())) {
      return switch (status) {
        case "DONE", "PARTIAL_CANCELED", "CANCELED" -> {
          settleConfirmed(a, p, false, actor, now);
          Attempt after = store.find(a.id(), false).orElseThrow();
          if (after.stage().cancellable()) {
            resolveCancels(after, p, "INQUIRY", now);
          }
          yield Followup.NONE;
        }
        case "ABORTED" -> {
          fail(a, PaymentStage.CONFIRM_FAILED, "CONFIRM", "ABORTED", "토스 승인 실패", now);
          yield Followup.NONE;
        }
        case "EXPIRED" -> {
          fail(a, PaymentStage.EXPIRED, "EXPIRE", "EXPIRED", "승인 없이 만료", now);
          yield Followup.NONE;
        }
        case "IN_PROGRESS", "READY" ->
            PaymentRules.resourcesExpired(a.resourceExpiresAt(), now)
                ? Followup.STILL_UNKNOWN
                : Followup.RECONFIRM;
        // 가상계좌 입금 대기 등 DONE 이 아닌 승인(미결정 D1) — 확정하지 않는다
        default -> {
          hold(a, p, "STATUS", actor, now);
          yield Followup.NONE;
        }
      };
    }
    if (a.stage().cancellable()) {
      store.recordToss(a.id(), facts(p), now);
      resolveCancels(store.find(a.id(), false).orElseThrow(), p, "INQUIRY", now);
    }
    return Followup.NONE;
  }

  /** 취소 API 응답 반영(§6.5-6). */
  public void applyCancel(Attempt a, Cancel c, Result r, String actor, Instant now) {
    switch (r) {
      case Result.Ok ok -> {
        store.log(
            a.id(),
            "CANCEL",
            ok.httpStatus(),
            ok.payment().status(),
            a.stage(),
            null,
            actor,
            c.requestId(),
            now);
        TossPayment p = ok.payment();
        Optional<String> idMismatch =
            PaymentRules.mismatch(
                a.id(), a.paymentKey(), a.amount(), p.orderId(), p.paymentKey(), a.amount(), "KRW");
        if (idMismatch.isPresent()) {
          // 취소 결과를 확정하지 않고 대사 대상으로(R3)
          store.markCancelUnknown(
              c.requestId(), c.unknownRetryCount(), now.plus(PaymentRules.FAST_INTERVAL));
          store.log(
              a.id(),
              "CANCEL",
              ok.httpStatus(),
              "ID_MISMATCH",
              a.stage(),
              null,
              actor,
              c.requestId(),
              now);
          return;
        }
        store.recordToss(a.id(), facts(p), now);
        resolveCancels(store.find(a.id(), false).orElseThrow(), p, "PG_RESPONSE", now);
      }
      case Result.Rejected rej -> {
        store.log(
            a.id(),
            "CANCEL",
            rej.httpStatus(),
            rej.code(),
            a.stage(),
            null,
            actor,
            c.requestId(),
            now);
        store.resolveCancel(c.requestId(), "FAILED", null, rej.code(), null, "PG_RESPONSE", now);
        Totals t = totals(a);
        events.cancelResult(a, c, "FAILED", rej.code(), t.succeeded, t.remaining, now);
      }
      case Result.Unknown u -> {
        store.log(
            a.id(),
            "CANCEL",
            u.httpStatus(),
            u.reason(),
            a.stage(),
            null,
            actor,
            c.requestId(),
            now);
        // 직후 1회 → 1분×5 → 15분(§7.5). 불명 사건은 처음 한 번만 낸다
        int count = c.unknownRetryCount();
        Instant next = count == 0 ? now : PaymentRules.nextCheck(count, now).at();
        store.markCancelUnknown(c.requestId(), count + 1, next);
        if (count == 0) {
          Totals t = totals(a);
          events.cancelResult(a, c, "UNKNOWN", u.reason(), t.succeeded, t.remaining, now);
        }
      }
    }
  }

  /** 불명 재조회 뒤에도 모를 때 다음 일정·상신(§7.5). */
  public void stillUnknown(Attempt a, Instant now) {
    int count = a.unknownRetryCount() + 1;
    PaymentRules.Next next = PaymentRules.nextCheck(count, now);
    if (next.escalate() && a.stage() == PaymentStage.CONFIRM_UNKNOWN) {
      store.setStage(a.id(), PaymentStage.MANUAL_REVIEW, now);
      store.log(
          a.id(),
          "TRANSITION",
          null,
          null,
          a.stage(),
          PaymentStage.MANUAL_REVIEW,
          "scheduler",
          "자동 재조회 한도",
          now);
    }
    store.schedule(a.id(), count, next.at(), now);
    events.unknown(a, count, next.at(), next.escalate());
  }

  /** 한도 계산용 합계. remaining = 승인액 − 성공 − 진행 중·불명. */
  public record Totals(long succeeded, long held, long remaining) {}

  public Totals totals(Attempt a) {
    long approved = a.totalAmount() == null ? a.amount() : a.totalAmount();
    long succeeded = 0;
    long held = 0;
    for (Cancel c : store.cancelsOf(a.id())) {
      switch (c.state()) {
        case "SUCCEEDED" -> succeeded += c.amount();
        case "REQUESTED", "UNKNOWN" -> held += c.amount();
        default -> {}
      }
    }
    return new Totals(succeeded, held, PaymentRules.remaining(approved, succeeded, held));
  }

  // ---------- 내부 ----------

  private static boolean pending(PaymentStage s) {
    return s == PaymentStage.CONFIRMING
        || s == PaymentStage.CONFIRM_UNKNOWN
        || s == PaymentStage.MANUAL_REVIEW;
  }

  /** 승인 결과(DONE 등)를 R3 로 대조해 APPROVED 또는 MISMATCH_HOLD. */
  private void settleConfirmed(
      Attempt a, TossPayment p, boolean fromConfirm, String actor, Instant now) {
    Optional<String> m =
        PaymentRules.mismatch(
            a.id(),
            a.paymentKey(),
            a.amount(),
            p.orderId(),
            p.paymentKey(),
            p.totalAmount(),
            p.currency());
    if (m.isPresent()) {
      hold(a, p, m.get(), actor, now);
      return;
    }
    boolean done = "DONE".equals(p.status());
    if (fromConfirm && !done) {
      // 승인 응답인데 DONE 이 아님(F6) — 확정하지 않는다
      hold(a, p, "STATUS", actor, now);
      return;
    }
    store.recordToss(a.id(), facts(p), now);
    store.setStage(a.id(), PaymentStage.APPROVED, now);
    store.clearSchedule(a.id(), now);
    store.log(
        a.id(), "TRANSITION", null, p.status(), a.stage(), PaymentStage.APPROVED, actor, null, now);
    Attempt fresh = store.find(a.id(), false).orElseThrow();
    events.approved(
        fresh,
        p.method() == null ? "UNKNOWN" : p.method(),
        p.approvedAt() == null ? now : p.approvedAt());
    log.info("payment {} APPROVED", a.id());
  }

  private void hold(Attempt a, TossPayment p, String field, String actor, Instant now) {
    store.recordToss(a.id(), facts(p), now);
    store.recordMismatch(a.id(), field, now);
    if (a.stage() != PaymentStage.MISMATCH_HOLD) {
      store.setStage(a.id(), PaymentStage.MISMATCH_HOLD, now);
      store.clearSchedule(a.id(), now);
      store.log(
          a.id(),
          "TRANSITION",
          null,
          p.status(),
          a.stage(),
          PaymentStage.MISMATCH_HOLD,
          actor,
          field,
          now);
      events.mismatch(a, p.totalAmount(), field);
    }
    log.warn("payment {} MISMATCH_HOLD ({})", a.id(), field);
  }

  private void fail(
      Attempt a,
      PaymentStage to,
      String stage,
      String code,
      @Nullable String message,
      Instant now) {
    store.setStage(a.id(), to, now);
    store.clearSchedule(a.id(), now);
    store.recordFailure(a.id(), stage, code, message, now);
    events.failed(a, code, message, now);
  }

  /** 토스 cancels[] 로 진행 중·불명 취소를 성공 처리하고 단계·잔액을 맞춘다. */
  private void resolveCancels(Attempt a, TossPayment p, String resolvedBy, Instant now) {
    List<Cancel> local = store.cancelsOf(a.id());
    Set<String> known = new HashSet<>();
    local.stream().map(Cancel::transactionKey).filter(k -> k != null).forEach(known::add);
    for (Cancel c : local) {
      if (!"REQUESTED".equals(c.state()) && !"UNKNOWN".equals(c.state())) {
        continue;
      }
      Optional<TossCancel> match =
          p.cancels().stream()
              .filter(tc -> "DONE".equals(tc.cancelStatus()))
              .filter(tc -> tc.transactionKey() != null && !known.contains(tc.transactionKey()))
              .filter(tc -> tc.cancelAmount() == c.amount())
              .findFirst();
      if (match.isEmpty()) {
        continue;
      }
      TossCancel tc = match.get();
      known.add(tc.transactionKey());
      store.resolveCancel(
          c.requestId(), "SUCCEEDED", tc.transactionKey(), null, tc.canceledAt(), resolvedBy, now);
      Totals t = totals(a);
      events.cancelResult(a, c, "SUCCESS", null, t.succeeded, t.remaining, now);
    }
    PaymentStage target =
        p.balanceAmount() != null && p.balanceAmount() == 0
            ? PaymentStage.CANCELED
            : "PARTIAL_CANCELED".equals(p.status()) ? PaymentStage.PARTIAL_CANCELED : a.stage();
    if (target != a.stage() && a.stage().cancellable()) {
      store.setStage(a.id(), target, now);
      store.log(a.id(), "TRANSITION", null, p.status(), a.stage(), target, "ledger", null, now);
    }
  }

  static PaymentStore.TossFacts facts(TossPayment p) {
    return new PaymentStore.TossFacts(
        p.status(),
        p.method(),
        p.approvedAt(),
        p.totalAmount(),
        p.balanceAmount(),
        p.isPartialCancelable());
  }
}
