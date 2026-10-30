package com.pmart.payment.application;

import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.PaymentStore.Cancel;
import com.pmart.payment.infrastructure.toss.TossClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전체·부분 취소(환불 실행, claim → payment)와 보상 취소(orchestration → payment) — 결제 명세 §6.5, ADR-0005. 금액 산정
 * 근거는 검증하지 않고 한도·멱등·원거래 적격만 본다.
 *
 * <ul>
 *   <li>Idempotency-Key = 취소 요청 ID = 토스 취소 멱등키. 같은 키 재전송은 기존 결과, 같은 키로 다른 내용이면 409.
 *   <li>한도: 요청액 ≤ 승인액 − 성공 누적 − 진행 중·불명 점유. 넘으면 토스를 부르지 않고 422.
 *   <li>부분 취소 불가 결제(isPartialCancelable=false)의 부분 취소는 토스를 부르지 않고 거절(미결정 D2 의 안전한 기본 동작).
 *   <li>결과 불명은 같은 멱등키로만 재요청한다(새 키·새 금액 금지).
 * </ul>
 */
@Service
public class CancellationService {

  public record Refund(
      String refundIntentId, String claimId, long amount, String scope, String reason) {}

  private static final Map<String, String> REASONS =
      Map.of(
          "CANCEL_BEFORE_SHIP", "출고 전 주문 취소",
          "SHORTAGE", "결품 부분 취소",
          "RETURN", "반품 환불",
          "EXCHANGE_DIFF", "교환 차액 환불",
          "DISPATCH_FAILED", "배송 불가 환불",
          "OPERATOR", "운영자 환불",
          "ORDER_CONFIRM_FAILED", "주문 확정 실패 보상 취소",
          "RESERVATION_LOST", "재고 예약 소멸 보상 취소",
          "OPERATION_RESTRICTED", "운영 제한 보상 취소",
          "AMOUNT_MISMATCH", "금액 불일치 보상 취소");

  private final PaymentStore store;
  private final PaymentLedger ledger;
  private final TossClient toss;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CancellationService(
      PaymentStore store,
      PaymentLedger ledger,
      TossClient toss,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.ledger = ledger;
    this.toss = toss;
    this.tx = tx;
    this.clock = clock;
  }

  public CommandResponse refund(String requestId, String attemptId, Refund in) {
    if (in.amount() <= 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "취소 금액은 1원 이상이어야 합니다.");
    }
    Prepared p =
        tx.execute(
            s -> {
              Attempt a = attempt(attemptId);
              var existing = store.cancel(requestId);
              if (existing.isPresent()) {
                Cancel c = existing.get();
                if (!c.attemptId().equals(attemptId)
                    || !Objects.equals(c.refundIntentId(), in.refundIntentId())
                    || c.amount() != in.amount()) {
                  throw PmartException.idempotencyConflict(
                      "같은 Idempotency-Key 로 다른 취소가 이미 요청되었습니다.");
                }
                return new Prepared(a, c, false);
              }
              if (store.refundIntentUsed(in.refundIntentId())) {
                throw new PmartException(
                    "REFUND_INTENT_ALREADY_USED",
                    HttpStatus.CONFLICT,
                    "이미 실행한 환불 의도입니다.",
                    false,
                    List.of());
              }
              requireCancellable(a);
              PaymentLedger.Totals t = ledger.totals(a);
              if (in.amount() > t.remaining()) {
                throw limitExceeded(t.remaining());
              }
              if ("FULL".equals(in.scope()) && in.amount() != t.remaining()) {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED,
                    "전체 취소 금액은 남은 한도(" + t.remaining() + "원)와 같아야 합니다.");
              }
              if ("PARTIAL".equals(in.scope()) && Boolean.FALSE.equals(a.partialCancelable())) {
                throw new PmartException(
                    "PARTIAL_CANCEL_NOT_ALLOWED",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "부분 취소가 안 되는 결제입니다. 처리 방법을 결정해야 합니다.",
                    false,
                    List.of());
              }
              Cancel c =
                  new Cancel(
                      requestId,
                      attemptId,
                      "REFUND",
                      in.refundIntentId(),
                      in.claimId(),
                      null,
                      in.amount(),
                      in.scope(),
                      REASONS.getOrDefault(in.reason(), in.reason()),
                      "REQUESTED",
                      null,
                      null,
                      null,
                      null,
                      0,
                      clock.instant(),
                      null);
              store.insertCancel(c, a.balanceAmount());
              return new Prepared(a, c, true);
            });
    return execute(Objects.requireNonNull(p));
  }

  public CommandResponse compensate(
      String requestId, String attemptId, String sagaId, String step, String reason) {
    Prepared p =
        tx.execute(
            s -> {
              Attempt a = attempt(attemptId);
              var existing = store.cancel(requestId);
              if (existing.isPresent()) {
                Cancel c = existing.get();
                if (!c.attemptId().equals(attemptId) || !"COMPENSATION".equals(c.kind())) {
                  throw PmartException.idempotencyConflict(
                      "같은 Idempotency-Key 로 다른 취소가 이미 요청되었습니다.");
                }
                return new Prepared(a, c, false);
              }
              requireCancellable(a);
              PaymentLedger.Totals t = ledger.totals(a);
              if (t.succeeded() > 0 || t.held() > 0) {
                throw PmartException.invalidState("부분 취소 누적·진행 중 취소가 있는 결제는 보상 취소할 수 없습니다.");
              }
              Cancel c =
                  new Cancel(
                      requestId,
                      attemptId,
                      "COMPENSATION",
                      null,
                      step,
                      sagaId,
                      t.remaining(),
                      "FULL",
                      REASONS.getOrDefault(reason, reason),
                      "REQUESTED",
                      null,
                      null,
                      null,
                      null,
                      0,
                      clock.instant(),
                      null);
              store.insertCancel(c, a.balanceAmount());
              return new Prepared(a, c, true);
            });
    return execute(Objects.requireNonNull(p));
  }

  public Cancel get(String attemptId, String requestId) {
    return store
        .cancel(requestId)
        .filter(c -> c.attemptId().equals(attemptId))
        .orElseThrow(() -> PmartException.notFound("CANCELLATION_NOT_FOUND", "취소 거래가 없습니다."));
  }

  /** 불명 취소 재요청(같은 멱등키) — 주기 작업이 부른다. */
  void retry(Cancel c) {
    Attempt a = attempt(c.attemptId());
    TossClient.Result r =
        toss.cancel(
            Objects.requireNonNull(a.paymentKey()), c.reason(), c.amount(), null, c.requestId());
    tx.executeWithoutResult(
        s -> {
          Attempt locked = store.find(a.id(), true).orElseThrow();
          Cancel now = store.cancel(c.requestId()).orElseThrow();
          if ("UNKNOWN".equals(now.state()) || "REQUESTED".equals(now.state())) {
            ledger.applyCancel(locked, now, r, "scheduler", clock.instant());
          }
        });
  }

  private record Prepared(Attempt attempt, Cancel cancel, boolean fresh) {}

  private CommandResponse execute(Prepared p) {
    Cancel c = p.cancel();
    if (p.fresh()) {
      TossClient.Result r =
          toss.cancel(
              Objects.requireNonNull(p.attempt().paymentKey()),
              c.reason(),
              c.amount(),
              p.attempt().balanceAmount(),
              c.requestId());
      tx.executeWithoutResult(
          s -> {
            Attempt locked = store.find(p.attempt().id(), true).orElseThrow();
            ledger.applyCancel(locked, c, r, "api", clock.instant());
          });
    }
    Cancel now = store.cancel(c.requestId()).orElseThrow();
    return response(now);
  }

  static CommandResponse response(Cancel c) {
    CommandStatus status =
        switch (c.state()) {
          case "SUCCEEDED" -> CommandStatus.APPLIED;
          case "FAILED" -> CommandStatus.REJECTED;
          case "UNKNOWN" -> CommandStatus.UNKNOWN_RESULT;
          default -> CommandStatus.ACCEPTED;
        };
    @Nullable String next =
        switch (status) {
          case UNKNOWN_RESULT, ACCEPTED -> "QUERY_RESULT";
          default -> null;
        };
    Instant at = c.resolvedAt() == null ? c.requestedAt() : c.resolvedAt();
    return new CommandResponse(
        status,
        c.requestId(),
        c.requestId(),
        null,
        null,
        status == CommandStatus.UNKNOWN_RESULT,
        next,
        at);
  }

  private Attempt attempt(String id) {
    return store
        .find(id, true)
        .orElseThrow(() -> PmartException.notFound("PAYMENT_NOT_FOUND", "결제 시도가 없습니다."));
  }

  private static void requireCancellable(Attempt a) {
    if (!a.stage().cancellable()) {
      throw PmartException.invalidState("승인된 결제만 취소할 수 있습니다(현재 " + a.stage() + ").");
    }
    if (a.paymentKey() == null) {
      throw PmartException.invalidState("paymentKey 가 없는 결제입니다.");
    }
  }

  private static PmartException limitExceeded(long remaining) {
    return new PmartException(
        "REFUND_LIMIT_EXCEEDED",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "추가 환불 가능 한도(" + remaining + "원)를 넘습니다.",
        false,
        List.of());
  }
}
