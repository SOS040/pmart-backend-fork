package com.pmart.payment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.payment.domain.PaymentRules;
import com.pmart.payment.domain.PaymentStage;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.toss.TossClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객 앱 결제(결제 명세 §6.2~6.4). 결제 건은 orderId(= 결제 시도 ID) 하나로만 가리키고(R4), 본인 결제 시도만 다룬다(다른 고객 것은 404). 승인
 * 요청은 정해진 순서로 검사하고, 통과한 경우에만 토스 승인 API 를 부른다.
 */
@Service
public class CheckoutService {

  private final PaymentStore store;
  private final PaymentLedger ledger;
  private final PaymentEvents events;
  private final TossClient toss;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CheckoutService(
      PaymentStore store,
      PaymentLedger ledger,
      PaymentEvents events,
      TossClient toss,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.ledger = ledger;
    this.events = events;
    this.toss = toss;
    this.tx = tx;
    this.clock = clock;
  }

  public Attempt get(String orderId) {
    return own(orderId);
  }

  /** §6.3 승인 요청. */
  public Attempt confirm(String orderId, String paymentKey, long amount) {
    // 1) 형식
    if (!PaymentRules.ORDER_ID.matcher(orderId).matches()
        || paymentKey.isEmpty()
        || paymentKey.length() > 200
        || amount <= 0) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "orderId·paymentKey·amount 형식이 잘못되었습니다.");
    }
    // 2) 조회(본인 것만)
    Attempt a = own(orderId);
    // 3) 이미 다른 paymentKey
    if (a.paymentKey() != null && !a.paymentKey().equals(paymentKey)) {
      throw new PmartException(
          "PAYMENT_KEY_MISMATCH",
          HttpStatus.CONFLICT,
          "이 결제에는 다른 paymentKey 가 이미 있습니다.",
          false,
          List.of());
    }
    // 4) 금액 — 토스를 부르지 않는다
    if (amount != a.amount()) {
      throw new PmartException(
          "AMOUNT_MISMATCH", HttpStatus.BAD_REQUEST, "결제 금액이 결제 시도와 다릅니다.", false, List.of());
    }
    // 5) 상태
    if (a.stage() != PaymentStage.READY) {
      if (PaymentStage.FAILED_FINAL.contains(a.stage())) {
        throw PmartException.invalidState("이미 끝난 결제입니다(" + a.stage() + "). 결제를 다시 시작하세요.");
      }
      return a; // 승인됨·진행 중·불명·불일치 — 현재 상태
    }
    Instant now = clock.instant();
    // 6) 승인 직전 자원 확인 — 만료면 승인 API 를 부르지 않는다
    if (PaymentRules.resourcesExpired(a.resourceExpiresAt(), now)) {
      tx.executeWithoutResult(
          s -> {
            if (store.transition(a.id(), PaymentStage.READY, PaymentStage.EXPIRED, now)) {
              store.recordFailure(a.id(), "EXPIRE", "RESERVATION_EXPIRED", "재고 예약·슬롯 만료", now);
              store.log(
                  a.id(),
                  "TRANSITION",
                  null,
                  null,
                  PaymentStage.READY,
                  PaymentStage.EXPIRED,
                  "customer",
                  null,
                  now);
              events.failed(a, "RESERVATION_EXPIRED", "재고 예약·슬롯 만료", now);
            }
          });
      throw new PmartException(
          "RESERVATION_EXPIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "결제 가능 시간이 지났습니다. 주문서를 다시 확인하세요.",
          false,
          List.of());
    }
    // 7) READY → CONFIRMING + paymentKey 저장(R8). 동시 요청은 하나만
    Boolean begun = tx.execute(s -> store.beginConfirm(a.id(), paymentKey, now));
    if (!Boolean.TRUE.equals(begun)) {
      return store.find(a.id(), false).orElseThrow();
    }
    // 8) 토스 승인(트랜잭션 밖)
    TossClient.Result r = toss.confirm(paymentKey, a.id(), a.amount(), a.confirmIdempotencyKey());
    // 9) 결과 반영
    tx.executeWithoutResult(
        s -> {
          Attempt locked = store.find(a.id(), true).orElseThrow();
          if (locked.stage() == PaymentStage.CONFIRMING) {
            ledger.applyConfirm(locked, r, "customer", clock.instant());
          }
        });
    return Objects.requireNonNull(store.find(a.id(), false).orElseThrow());
  }

  /** §6.2 인증 실패·사용자 취소 보고. READY 만 바꾸고, 아니면 현재 상태(멱등). */
  public Attempt reportFailure(String orderId, String errorCode, String errorMessage) {
    Attempt a = own(orderId);
    if (a.stage() != PaymentStage.READY) {
      return a;
    }
    PaymentStage to = PaymentRules.authFailureStage(errorCode);
    Instant now = clock.instant();
    tx.executeWithoutResult(
        s -> {
          if (store.transition(a.id(), PaymentStage.READY, to, now)) {
            store.recordFailure(a.id(), "AUTH", errorCode, errorMessage, now);
            store.log(
                a.id(),
                "TRANSITION",
                null,
                errorCode,
                PaymentStage.READY,
                to,
                "customer",
                null,
                now);
            events.failed(a, errorCode, errorMessage, now);
          }
        });
    return store.find(a.id(), false).orElseThrow();
  }

  private Attempt own(String orderId) {
    String customer =
        PmartPrincipal.current()
            .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .map(PmartPrincipal::subject)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "고객만 할 수 있습니다."));
    return store
        .find(orderId, false)
        .filter(a -> a.customerRef().equals(customer))
        .orElseThrow(() -> PmartException.notFound("PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
  }
}
