package com.pmart.claim.api;

import com.pmart.claim.api.generated.model.Claim;
import com.pmart.claim.api.generated.model.ClaimLine;
import com.pmart.claim.api.generated.model.ClaimReason;
import com.pmart.claim.api.generated.model.ClaimState;
import com.pmart.claim.api.generated.model.ClaimSummary;
import com.pmart.claim.api.generated.model.ClaimType;
import com.pmart.claim.api.generated.model.Refund;
import com.pmart.claim.api.generated.model.RefundBreakdown;
import com.pmart.claim.api.generated.model.RefundState;
import com.pmart.claim.application.ClaimService.ClaimView;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 원장 → 계약 DTO. 업무 판단은 하지 않는다. */
final class ClaimMapping {

  private ClaimMapping() {}

  static Claim claim(ClaimView v) {
    ClaimRow c = v.claim();
    Claim out =
        new Claim()
            .claimId(c.claimId())
            .version(c.version())
            .orderId(c.orderId())
            .type(ClaimType.fromValue(c.type()))
            .state(ClaimState.fromValue(c.state()))
            .reason(ClaimReason.fromValue(c.reason()))
            .reasonDetail(c.reasonDetail())
            .stopRequestId(c.stopRequestId())
            .openedAt(odt(c.openedAt()))
            .decidedAt(odt(c.decidedAt()))
            .closedAt(odt(c.closedAt()));
    for (LineRow l : v.lines()) {
      out.addLinesItem(
          new ClaimLine()
              .claimLineId(l.claimLineId())
              .orderLineId(l.orderLineId())
              .sku(l.sku())
              .requestedQty(l.requestedQty())
              .approvedQty(l.approvedQty())
              .decision(ClaimLine.DecisionEnum.fromValue(l.decision()))
              .rejectReason("REJECTED".equals(c.state()) ? c.rejectReason() : null)
              .pickupRequired(false)
              .refundAmount(Math.toIntExact(l.lineAmount())));
    }
    for (RefundRow r : v.refunds()) {
      out.addRefundsItem(refund(r));
    }
    return out;
  }

  static ClaimSummary summary(ClaimRow c) {
    return new ClaimSummary()
        .claimId(c.claimId())
        .orderId(c.orderId())
        .type(ClaimType.fromValue(c.type()))
        .state(ClaimState.fromValue(c.state()))
        .openedAt(odt(c.openedAt()));
  }

  static Refund refund(RefundRow r) {
    Refund out =
        new Refund()
            .refundIntentId(r.refundIntentId())
            .claimId(r.claimId())
            .state(RefundState.fromValue(r.state()))
            .amount(Math.toIntExact(r.amount()))
            .breakdown(
                new RefundBreakdown()
                    .itemAmount(Math.toIntExact(r.itemAmount()))
                    .discountShare(0)
                    .bagFee(Math.toIntExact(r.bagFee()))
                    .deliveryTip(Math.toIntExact(r.deliveryTip()))
                    .feeDeduction(0))
            .paymentAttemptId(r.paymentAttemptId())
            .cancellationRequestId(r.cancellationRequestId())
            .requestedAt(odt(r.requestedAt()))
            .completedAt(odt(r.completedAt()));
    if (r.pgResult() != null) {
      out.pgResult(Refund.PgResultEnum.fromValue(r.pgResult()));
    }
    return out;
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
