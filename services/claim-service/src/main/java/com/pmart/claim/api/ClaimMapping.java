package com.pmart.claim.api;

import com.pmart.claim.api.generated.model.Claim;
import com.pmart.claim.api.generated.model.ClaimFees;
import com.pmart.claim.api.generated.model.ClaimLine;
import com.pmart.claim.api.generated.model.ClaimLineInspection;
import com.pmart.claim.api.generated.model.ClaimPickup;
import com.pmart.claim.api.generated.model.ClaimReason;
import com.pmart.claim.api.generated.model.ClaimState;
import com.pmart.claim.api.generated.model.ClaimSummary;
import com.pmart.claim.api.generated.model.ClaimType;
import com.pmart.claim.api.generated.model.PickupState;
import com.pmart.claim.api.generated.model.Refund;
import com.pmart.claim.api.generated.model.RefundBreakdown;
import com.pmart.claim.api.generated.model.RefundState;
import com.pmart.claim.api.generated.model.ReturnFaultCode;
import com.pmart.claim.api.generated.model.ReturnFeePayment;
import com.pmart.claim.application.ClaimService.ClaimView;
import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.FeeRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.PickupRow;
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
    boolean isReturn = "RETURN".equals(c.type());
    Claim out =
        new Claim()
            .claimId(c.claimId())
            .claimSeq(v.claimSeq())
            .version(c.version())
            .orderId(c.orderId())
            .type(ClaimType.fromValue(c.type()))
            .state(ClaimState.fromValue(c.state()))
            .reason(ClaimReason.fromValue(c.reason()))
            .reasonDetail(c.reasonDetail())
            .channel(Claim.ChannelEnum.fromValue(c.channel()))
            .stopRequestId(c.stopRequestId())
            .returnDeadlineAt(odt(c.returnDeadlineAt()))
            .openedAt(odt(c.openedAt()))
            .decidedAt(odt(c.decidedAt()))
            .closedAt(odt(c.closedAt()));
    for (LineRow l : v.lines()) {
      ClaimLine line =
          new ClaimLine()
              .claimLineId(l.claimLineId())
              .orderLineId(l.orderLineId())
              .sku(l.sku())
              .requestedQty(l.requestedQty())
              .approvedQty(l.approvedQty())
              .decision(ClaimLine.DecisionEnum.fromValue(l.decision()))
              .rejectReason("REJECTED".equals(c.state()) ? c.rejectReason() : null)
              .pickupRequired(isReturn)
              .refundAmount(Math.toIntExact(l.lineAmount()));
      if (isReturn) {
        line.pickedUpQty(l.pickedUpQty()).receivedQty(l.receivedQty());
        if (l.inspectedQty() > 0) {
          line.inspection(
              new ClaimLineInspection().resaleQty(l.resaleQty()).disposeQty(l.disposeQty()));
        }
      }
      out.addLinesItem(line);
    }
    for (RefundRow r : v.refunds()) {
      out.addRefundsItem(refund(r, v.refundSeqs().get(r.refundIntentId())));
    }
    if (isReturn) {
      if (c.faultCode() != null) {
        out.faultCode(ReturnFaultCode.fromValue(c.faultCode()));
      }
      out.fees(new ClaimFees().returnPickupFee(Math.toIntExact(ReturnRules.fee(c.faultCode()))));
      FeeRow f = v.fee();
      if (f != null) {
        out.feePayment(
            new ReturnFeePayment()
                .amount(Math.toIntExact(f.amount()))
                .state(ReturnFeePayment.StateEnum.fromValue(f.state()))
                .paymentAttemptId(f.paymentAttemptId())
                .orderName(ReturnRules.FEE_ORDER_NAME)
                .deadlineAt(odt(f.deadlineAt()))
                .paidAt(odt(f.paidAt()))
                .refundDecision(
                    f.refundDecision() == null
                        ? null
                        : ReturnFeePayment.RefundDecisionEnum.fromValue(f.refundDecision()))
                .refundIntentId(f.refundIntentId()));
      }
      PickupRow last = v.pickups().isEmpty() ? null : v.pickups().get(v.pickups().size() - 1);
      int counted =
          ReturnRules.countedFailures(
              v.pickups().stream()
                  .filter(PickupRow::failed)
                  .map(p -> p.failureKind() == null ? ReturnRules.FAIL_OTHER : p.failureKind())
                  .toList());
      out.pickup(
          new ClaimPickup()
              .state(c.pickupState() == null ? null : PickupState.fromValue(c.pickupState()))
              .deliveryId(last == null ? null : last.deliveryId())
              .addressFixedToOrder(true)
              .attempts(v.pickups().size())
              .failedAttempts((int) v.pickups().stream().filter(PickupRow::failed).count())
              .countedFailures(counted)
              .retryAllowed(ReturnRules.csRetryAllowed(counted)));
    }
    return out;
  }

  static ClaimSummary summary(
      ClaimRow c,
      java.util.List<com.pmart.claim.infrastructure.ClaimStore.RefundBrief> refunds,
      @org.jspecify.annotations.Nullable Integer claimSeq) {
    long requested = 0;
    long refunded = 0;
    java.time.Instant last = null;
    for (com.pmart.claim.infrastructure.ClaimStore.RefundBrief r : refunds) {
      requested += r.amount();
      if ("COMPLETED".equals(r.state())) {
        refunded += r.amount();
        if (r.completedAt() != null && (last == null || r.completedAt().isAfter(last))) {
          last = r.completedAt();
        }
      }
    }
    String state =
        com.pmart.claim.domain.ClaimListRules.refundState(
            refunds.stream()
                .map(com.pmart.claim.infrastructure.ClaimStore.RefundBrief::state)
                .toList());
    return new ClaimSummary()
        .claimId(c.claimId())
        .claimSeq(claimSeq)
        .orderId(c.orderId())
        .type(ClaimType.fromValue(c.type()))
        .state(ClaimState.fromValue(c.state()))
        .openedAt(odt(c.openedAt()))
        .reason(com.pmart.claim.api.generated.model.ClaimReason.fromValue(c.reason()))
        .channel(ClaimSummary.ChannelEnum.fromValue(c.channel()))
        .closedAt(c.closedAt() == null ? null : odt(c.closedAt()))
        .refund(
            new com.pmart.claim.api.generated.model.ClaimRefundSummary()
                .state(
                    com.pmart.claim.api.generated.model.ClaimRefundSummary.StateEnum.fromValue(
                        state))
                .requestedAmount(Math.toIntExact(requested))
                .refundedAmount(Math.toIntExact(refunded))
                .completedAt(last == null ? null : odt(last)));
  }

  static Refund refund(RefundRow r, @org.jspecify.annotations.Nullable Integer refundSeq) {
    Refund out =
        new Refund()
            .refundIntentId(r.refundIntentId())
            .refundSeq(refundSeq)
            .claimId(r.claimId())
            .kind(
                ReturnRules.RETURN_FEE_REFUND_REASON.equals(r.reason())
                    ? Refund.KindEnum.RETURN_FEE
                    : Refund.KindEnum.ITEMS)
            .state(RefundState.fromValue(r.state()))
            .amount(Math.toIntExact(r.amount()))
            .breakdown(
                new RefundBreakdown()
                    .itemAmount(Math.toIntExact(r.itemAmount()))
                    .discountShare(
                        "RETURN".equals(r.reason())
                            ? Math.toIntExact(r.itemAmount() - r.amount())
                            : 0)
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
