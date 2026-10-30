package com.pmart.payment.api;

import com.pmart.payment.api.generated.CancellationsApi;
import com.pmart.payment.api.generated.model.Cancellation;
import com.pmart.payment.api.generated.model.CancellationRequest;
import com.pmart.payment.api.generated.model.CompensationCancellationRequest;
import com.pmart.payment.api.generated.model.CreateCancellation202Response;
import com.pmart.payment.application.CancellationService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CancellationsApi — 환불 실행(claim)·보상 취소(orchestration)·취소 거래 조회. 변환만 한다. */
@RestController
public class CancellationsController implements CancellationsApi {

  private final CancellationService cancellations;

  public CancellationsController(CancellationService cancellations) {
    this.cancellations = cancellations;
  }

  @Override
  public ResponseEntity<CreateCancellation202Response> createCancellation(
      String paymentAttemptId,
      String idempotencyKey,
      String xSourceSystem,
      CancellationRequest body,
      @Nullable String traceparent) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            PaymentMapping.command(
                cancellations.refund(
                    idempotencyKey,
                    paymentAttemptId,
                    new CancellationService.Refund(
                        body.getRefundIntentId(),
                        body.getClaimId(),
                        body.getAmount(),
                        body.getScope().getValue(),
                        body.getReason().getValue()))));
  }

  @Override
  public ResponseEntity<CreateCancellation202Response> createCompensationCancellation(
      String paymentAttemptId,
      String idempotencyKey,
      String xSourceSystem,
      CompensationCancellationRequest body,
      @Nullable String traceparent) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            PaymentMapping.command(
                cancellations.compensate(
                    idempotencyKey,
                    paymentAttemptId,
                    body.getSagaId(),
                    body.getStep(),
                    body.getReason().getValue())));
  }

  @Override
  public ResponseEntity<Cancellation> getCancellation(String paymentAttemptId, String requestId) {
    return ResponseEntity.ok(
        PaymentMapping.cancellation(cancellations.get(paymentAttemptId, requestId)));
  }
}
