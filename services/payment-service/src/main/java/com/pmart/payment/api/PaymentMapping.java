package com.pmart.payment.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.payment.api.generated.model.Cancellation;
import com.pmart.payment.api.generated.model.CheckoutPayment;
import com.pmart.payment.api.generated.model.CheckoutPaymentFailure;
import com.pmart.payment.api.generated.model.CreateCancellation202Response;
import com.pmart.payment.api.generated.model.Payment;
import com.pmart.payment.api.generated.model.PaymentAsOf;
import com.pmart.payment.api.generated.model.PaymentMismatch;
import com.pmart.payment.api.generated.model.PaymentStage;
import com.pmart.payment.api.generated.model.PaymentState;
import com.pmart.payment.api.generated.model.RefundLimits;
import com.pmart.payment.application.PaymentLedger;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.PaymentStore.Cancel;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 응답 변환. 금액은 계약 Money(int32) — 넘치면 예외로 드러낸다. 카드 정보·시크릿 키는 없다. */
final class PaymentMapping {

  private static final Set<String> MISMATCH_FIELDS = Set.of("AMOUNT", "CURRENCY", "ORDER_REF");

  private PaymentMapping() {}

  static CheckoutPayment checkout(Attempt a) {
    CheckoutPayment c =
        new CheckoutPayment()
            .orderId(a.id())
            .state(PaymentState.fromValue(a.stage().state()))
            .stage(PaymentStage.fromValue(a.stage().name()))
            .amount(money(a.amount()))
            .method(a.method())
            .approvedAt(odt(a.approvedAt()));
    if (a.failCode() != null) {
      c.failure(
          new CheckoutPaymentFailure()
              .stage(
                  a.failStage() == null
                      ? null
                      : CheckoutPaymentFailure.StageEnum.fromValue(a.failStage()))
              .code(a.failCode())
              .message(a.failMessage()));
    }
    return c;
  }

  static Payment payment(Attempt a, List<Cancel> cancels, PaymentLedger.Totals t) {
    long approved = a.totalAmount() == null ? 0 : a.totalAmount();
    long unknown =
        cancels.stream().filter(c -> "UNKNOWN".equals(c.state())).mapToLong(Cancel::amount).sum();
    long inProgress =
        cancels.stream().filter(c -> "REQUESTED".equals(c.state())).mapToLong(Cancel::amount).sum();
    Payment p =
        new Payment()
            .paymentAttemptId(a.id())
            .orderId(a.orderRef())
            .sagaId(a.sagaId())
            .state(PaymentState.fromValue(a.stage().state()))
            .stage(PaymentStage.fromValue(a.stage().name()))
            .pgOrderId(a.id())
            .amount(money(a.amount()))
            .isPartialCancelable(a.partialCancelable())
            .balanceAmount(a.balanceAmount() == null ? null : money(a.balanceAmount()))
            .version(a.version())
            .method(a.method())
            .pgTransactionId(a.paymentKey())
            .approvedAmount(a.totalAmount() == null ? null : money(a.totalAmount()))
            .currency(a.currency())
            .approvedAt(odt(a.approvedAt()))
            .cancellations(cancels.stream().map(PaymentMapping::cancellation).toList())
            .lastInquiryAt(odt(a.lastInquiryAt()))
            .asOf(
                new PaymentAsOf()
                    .asOf(odt(a.lastInquiryAt() == null ? Instant.now() : a.lastInquiryAt()))
                    .freshness(PaymentAsOf.FreshnessEnum.CURRENT));
    if (approved > 0) {
      p.limits(
          new RefundLimits()
              .approvedAmount(money(approved))
              .succeededCancelAmount(money(t.succeeded()))
              .inProgressCancelAmount(money(inProgress))
              .unknownHeldAmount(money(unknown))
              .remainingLimit(money(Math.max(0, t.remaining()))));
    }
    if (a.mismatchField() != null) {
      p.mismatch(
          new PaymentMismatch()
              .expectedAmount(money(a.amount()))
              .pgAmount(a.totalAmount() == null ? null : money(a.totalAmount()))
              .field(
                  MISMATCH_FIELDS.contains(a.mismatchField())
                      ? PaymentMismatch.FieldEnum.fromValue(a.mismatchField())
                      : null));
    }
    return p;
  }

  static Cancellation cancellation(Cancel c) {
    return new Cancellation()
        .requestId(c.requestId())
        .paymentAttemptId(c.attemptId())
        .kind(Cancellation.KindEnum.fromValue(c.kind()))
        .refundIntentId(c.refundIntentId())
        .sagaId(c.sagaId())
        .amount(money(c.amount()))
        .scope(Cancellation.ScopeEnum.fromValue(c.scope()))
        .state(Cancellation.StateEnum.fromValue(c.state()))
        .pgCancelTransactionId(c.transactionKey())
        .pgResultCode(c.pgResultCode())
        .requestedAt(odt(c.requestedAt()))
        .resolvedAt(odt(c.resolvedAt()))
        .resolvedBy(
            c.resolvedBy() == null ? null : Cancellation.ResolvedByEnum.fromValue(c.resolvedBy()));
  }

  static CreateCancellation202Response command(CommandResponse r) {
    return new CreateCancellation202Response()
        .status(CreateCancellation202Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.resultId())
        .retryable(r.retryable())
        .nextAction(r.nextAction())
        .occurredAt(odt(r.occurredAt()));
  }

  static int money(long krw) {
    return Math.toIntExact(krw);
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
