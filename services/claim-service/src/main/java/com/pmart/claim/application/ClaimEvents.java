package com.pmart.claim.application;

import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * claim-service 발행 사건(Outbox, 토픽 order.claim.v1). 호출자 트랜잭션 안에서 업무 변경과 함께 쓴다. 반품(CL-1, 2026-11-17):
 * ReturnPickupRequested·ReturnFeePaymentRequested·ReturnRecoveryFailed.
 */
@Component
public class ClaimEvents {

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public ClaimEvents(EventPublisher events, EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
  }

  public void claimOpened(ClaimRow c, List<LineRow> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("type", c.type());
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      ls.add(
          Map.of(
              "claimLineId",
              l.claimLineId(),
              "orderLineId",
              l.orderLineId(),
              "qty",
              l.requestedQty()));
    }
    p.put("lines", ls);
    p.put("reason", c.reason());
    p.put("openedAt", c.openedAt().toString());
    if ("RETURN".equals(c.type())) {
      // 반품(CL-1): 채널·면제 사유·회수비 — 알림·사건함 표시용(필드 추가)
      p.put("channel", c.channel());
      p.put("accountId", c.accountId());
      if (c.faultCode() != null) {
        p.put("faultCode", c.faultCode());
      }
      p.put("returnFee", ReturnRules.fee(c.faultCode()));
    }
    publish("ClaimOpened", c.claimId(), c.version(), p);
  }

  /** 회수 요청(CL-1) — delivery-dispatch 가 고객 반품 회수 배송(purpose CLAIM_RETURN)을 만든다. */
  public void returnPickupRequested(
      ClaimRow c, List<LineRow> lines, String pickupRequestId, int attempt, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    if (c.mfcId() != null) {
      p.put("mfcId", c.mfcId());
    }
    p.put("pickupRequestId", pickupRequestId);
    p.put("attempt", attempt);
    p.put("purpose", "CLAIM_RETURN");
    p.put("feeWaived", ReturnRules.feeWaived(c.faultCode()));
    // 회수할 수량 = 요청 − 이미 받은(앞 시도에서 일부 받았으면 남은 것만). 다 받았는데 다시 요청하면 요청 수량 그대로
    boolean anyLeft = lines.stream().anyMatch(l -> l.requestedQty() > l.pickedUpQty());
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      int qty = anyLeft ? l.requestedQty() - l.pickedUpQty() : l.requestedQty();
      if (qty <= 0) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("orderLineId", l.orderLineId());
      m.put("qty", qty);
      m.put("claimLineId", l.claimLineId());
      m.put("sku", l.sku());
      ls.add(m);
    }
    p.put("lines", ls);
    p.put("pickupAddressRef", "order:" + c.orderId()); // 원주문 주소 고정(원문 없음)
    p.put("requestedAt", at.toString());
    publish("ReturnPickupRequested", c.claimId(), c.version(), p);
  }

  /** 회수비 결제 필요(CL-1) — 첫 결제 시도를 만든 뒤 한 번. */
  public void returnFeePaymentRequested(
      ClaimRow c, long amount, String paymentAttemptId, Instant deadlineAt, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("accountId", c.accountId());
    p.put("amount", amount);
    p.put("paymentAttemptId", paymentAttemptId);
    p.put("deadlineAt", deadlineAt.toString());
    p.put("requestedAt", at.toString());
    publish("ReturnFeePaymentRequested", c.claimId(), c.version(), p);
  }

  /**
   * 회수 실패 — 상담원 재연락(CL-1 ③).
   *
   * @param countedAttempts 횟수에 드는 실패 수(라이더·우리 잘못 RIDER_INCIDENT 는 빼고 — 추가 결정 3·4)
   * @param retryAllowed 상담원이 다시 회수를 요청할 수 있나(전체 3번 안, 아니면 반품 취소만)
   */
  public void returnRecoveryFailed(
      ClaimRow c,
      int attempts,
      int countedAttempts,
      boolean retryAllowed,
      @Nullable String lastDeliveryId,
      @Nullable String lastFailureReason,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("accountId", c.accountId());
    if (c.mfcId() != null) {
      p.put("mfcId", c.mfcId());
    }
    p.put("attempts", attempts);
    p.put("countedAttempts", countedAttempts);
    p.put("retryAllowed", retryAllowed);
    if (c.returnDeadlineAt() != null) {
      p.put("returnDeadlineAt", c.returnDeadlineAt().toString());
    }
    if (lastDeliveryId != null) {
      p.put("lastDeliveryId", lastDeliveryId);
    }
    if (lastFailureReason != null) {
      p.put("lastFailureReason", lastFailureReason);
    }
    p.put("failedAt", at.toString());
    publish("ReturnRecoveryFailed", c.claimId(), c.version() + 1, p);
  }

  /** S3 시작 신호(orchestration 구독). stopRequestId 가 S3 멱등 단위. */
  public void cancellationRequested(ClaimRow c, List<LineRow> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("stopRequestId", c.stopRequestId());
    p.put("scope", "FULL");
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      ls.add(Map.of("orderLineId", l.orderLineId(), "qty", l.requestedQty()));
    }
    p.put("lines", ls);
    // S3 취소 사유: 시스템 자동 취소는 그 사유(FULFILLMENT_REJECTED D6-4, OUT_OF_STOCK 결품 전체 취소 ADR-0046),
    // 배차 불가 직원 취소(DISPATCH_UNAVAILABLE, CL-5 2026-11-19)도 그대로 — 주문 취소 사건의 cancelReason 으로 알림 문구가
    // 갈린다.
    // 그 밖(고객 접수)은 CUSTOMER_REQUEST
    p.put(
        "reason",
        "FULFILLMENT_REJECTED".equals(c.reason())
                || "OUT_OF_STOCK".equals(c.reason())
                || "DISPATCH_UNAVAILABLE".equals(c.reason())
            ? c.reason()
            : "CUSTOMER_REQUEST");
    p.put("channel", c.channel());
    p.put("requestedAt", c.openedAt().toString());
    publish("CancellationRequested", c.claimId(), c.version(), p);
  }

  public void refundIntentCreated(RefundRow r) {
    refundIntentCreated(r, 0, List.of());
  }

  /**
   * @param discountShare 할인 안분 합(반품 — 상품값에서 뺀 몫)
   * @param lines 반품 환불 근거 행(orderLineId·qty·amount), 없으면 빈 목록
   */
  public void refundIntentCreated(
      RefundRow r, long discountShare, List<Map<String, Object>> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("refundIntentId", r.refundIntentId());
    p.put("claimId", r.claimId());
    p.put("orderId", r.orderId());
    p.put("paymentAttemptId", r.paymentAttemptId());
    p.put("amount", r.amount());
    // 반품 회수비 돌려주기(CL-1 추가 결정 2)는 상품 환불이 아니다 — 회수비 결제 시도의 취소
    p.put("kind", ReturnRules.RETURN_FEE_REFUND_REASON.equals(r.reason()) ? "RETURN_FEE" : "ITEMS");
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("itemAmount", r.itemAmount());
    basis.put("discountShare", discountShare);
    basis.put("bagFee", r.bagFee());
    basis.put("deliveryTip", r.deliveryTip());
    if (!lines.isEmpty()) {
      basis.put("lines", lines);
    }
    p.put("basis", basis);
    p.put("createdAt", r.createdAt().toString());
    publish("RefundIntentCreated", r.claimId(), 1, p);
  }

  public void claimClosed(ClaimRow c, String outcome, Instant at) {
    claimClosed(c, outcome, at, List.of());
  }

  /**
   * @param returnedLines 반품 종결(RETURN_REFUNDED·RETURN_SHORT_CLOSED) — 행별 반품
   *     수량(orderLineId·qty·resaleQty· disposeQty). order 가 반품 수량(returnedQty)으로 기록한다(O-7, 추가 결정 9)
   */
  public void claimClosed(
      ClaimRow c, String outcome, Instant at, List<Map<String, Object>> returnedLines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("outcome", outcome);
    p.put("type", c.type());
    if (!returnedLines.isEmpty()) {
      p.put("returnedLines", returnedLines);
    }
    p.put("closedAt", at.toString());
    publish("ClaimClosed", c.claimId(), c.version() + 1, p);
  }

  private void publish(String type, String claimId, int version, Map<String, Object> payload) {
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Claim",
            claimId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, ClaimService.TOPIC);
  }
}
