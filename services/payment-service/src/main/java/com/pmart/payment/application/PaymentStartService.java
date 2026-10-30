package com.pmart.payment.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.payment.domain.PaymentRules;
import com.pmart.payment.domain.PaymentStage;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.toss.TossProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 시작(내부: orchestration → payment, 결제 명세 §6.1). 금액·예약·가격 동결 판정은 호출자(OMS)가 끝낸 뒤 부른다. 여기서는 결제 시도를
 * 만들고 결제창 진입 정보만 준다 — 승인이 아니다.
 *
 * <ul>
 *   <li>같은 Idempotency-Key·같은 주문 재전송은 같은 결제 시도(orderId)를 돌려준다. 같은 키로 다른 주문·금액이면 409.
 *   <li>같은 주문에 진행 중·불명·불일치 시도가 있으면 새 orderId 를 발급하지 않는다(R7). 승인된 시도가 있으면 PAYMENT_ALREADY_APPROVED.
 *   <li>예약이 이미 만료됐으면 시도를 만들지 않는다(§8).
 * </ul>
 */
@Service
public class PaymentStartService {

  public record Start(
      String orderRef,
      @Nullable String draftRef,
      String sagaId,
      long amount,
      String currency,
      String customerRef,
      @Nullable String orderName,
      boolean requiresAdult,
      Instant resourceExpiresAt,
      @Nullable Instant priceValidUntil) {}

  public record Started(Attempt attempt, @Nullable String clientKey) {}

  private final PaymentStore store;
  private final TossProperties toss;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PaymentStartService(
      PaymentStore store, TossProperties toss, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.toss = toss;
    this.tx = tx;
    this.clock = clock;
  }

  public Started start(String idempotencyKey, String sourceSystem, Start in) {
    if (toss.secretKey() == null || toss.clientKey() == null) {
      throw PmartException.configRequired("pmart.payment.toss.secret-key/client-key");
    }
    if (in.amount() <= 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "결제 금액은 1원 이상이어야 합니다.");
    }
    Attempt a =
        tx.execute(
            status -> {
              var existing = store.byEntry(sourceSystem, idempotencyKey);
              if (existing.isPresent()) {
                Attempt e = existing.get();
                if (!e.orderRef().equals(in.orderRef()) || e.amount() != in.amount()) {
                  throw PmartException.idempotencyConflict(
                      "같은 Idempotency-Key 로 다른 주문·금액의 결제가 이미 시작되었습니다.");
                }
                return e;
              }
              store.lockOrder(in.orderRef());
              List<Attempt> sameOrder = store.byOrder(in.orderRef());
              for (Attempt o : sameOrder) {
                if (o.stage().cancellable() || o.stage() == PaymentStage.CANCELED) {
                  throw new PmartException(
                      "PAYMENT_ALREADY_APPROVED",
                      HttpStatus.CONFLICT,
                      "이 주문은 이미 승인된 결제가 있습니다(" + o.id() + ").",
                      false,
                      List.of());
                }
                if (PaymentRules.BLOCKS_NEW_ATTEMPT.contains(o.stage())) {
                  throw new PmartException(
                      "PAYMENT_IN_PROGRESS",
                      HttpStatus.CONFLICT,
                      "이 주문에 결과 확인 중인 결제가 있어 새 결제를 시작할 수 없습니다(" + o.id() + ").",
                      false,
                      List.of());
                }
              }
              Instant now = clock.instant();
              if (PaymentRules.resourcesExpired(in.resourceExpiresAt(), now)) {
                throw new PmartException(
                    "RESERVATION_EXPIRED",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "재고 예약·슬롯이 이미 만료되었습니다.",
                    false,
                    List.of());
              }
              Attempt created =
                  new Attempt(
                      "pay-" + UUID.randomUUID().toString().replace("-", ""),
                      in.orderRef(),
                      in.draftRef(),
                      in.sagaId(),
                      in.customerRef(),
                      sourceSystem,
                      idempotencyKey,
                      in.amount(),
                      in.currency(),
                      in.orderName(),
                      in.requiresAdult(),
                      PaymentStage.READY,
                      null,
                      UUID.randomUUID().toString(),
                      null,
                      in.resourceExpiresAt(),
                      in.priceValidUntil(),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      0,
                      null,
                      null,
                      1,
                      now);
              store.insert(created);
              store.log(
                  created.id(),
                  "TRANSITION",
                  null,
                  null,
                  null,
                  PaymentStage.READY,
                  sourceSystem,
                  null,
                  now);
              return created;
            });
    return new Started(Objects.requireNonNull(a), toss.clientKey());
  }
}
