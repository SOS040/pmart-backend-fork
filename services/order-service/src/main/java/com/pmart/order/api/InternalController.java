package com.pmart.order.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.order.api.generated.InternalApi;
import com.pmart.order.api.generated.model.ConfirmOrderRequest;
import com.pmart.order.api.generated.model.StartCheckout202Response;
import com.pmart.order.application.OrderService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 주문 확정(orchestration → order). 나머지 내부 API(수량 변경·탈퇴 확인·배송 사실·고객 약속)는 인터페이스 default
 * 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final OrderService orders;

  public InternalController(OrderService orders) {
    this.orders = orders;
  }

  @Override
  public ResponseEntity<StartCheckout202Response> confirmOrder(
      String orderId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmOrderRequest body,
      @Nullable Integer xExpectedVersion) {
    CommandResponse r =
        orders.confirm(
            orderId,
            idempotencyKey,
            xSourceSystem,
            new OrderService.Confirmation(
                body.getPaymentAttemptId(),
                body.getPgTransactionId(),
                body.getApprovedAmount(),
                body.getReservationId(),
                body.getCapacityHoldId(),
                body.getSlotHoldId()),
            xExpectedVersion);
    return ResponseEntity.ok(
        new StartCheckout202Response()
            .status(StartCheckout202Response.StatusEnum.fromValue(r.status().name()))
            .requestId(r.requestId())
            .resultId(r.resultId())
            .version(r.version())
            .retryable(r.retryable())
            .nextAction(r.nextAction())
            .occurredAt(OffsetDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC)));
  }
}
