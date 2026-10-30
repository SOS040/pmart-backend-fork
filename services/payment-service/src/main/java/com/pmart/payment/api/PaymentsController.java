package com.pmart.payment.api;

import com.pmart.common.web.PmartException;
import com.pmart.payment.api.generated.PaymentsApi;
import com.pmart.payment.api.generated.model.CreatePaymentInquiryRequest;
import com.pmart.payment.api.generated.model.Payment;
import com.pmart.payment.api.generated.model.PaymentInquiry;
import com.pmart.payment.api.generated.model.PaymentState;
import com.pmart.payment.api.generated.model.StartPaymentRequest;
import com.pmart.payment.api.generated.model.StartPaymentResponse;
import com.pmart.payment.api.generated.model.StartPaymentResponseCheckout;
import com.pmart.payment.application.PaymentLedger;
import com.pmart.payment.application.PaymentStartService;
import com.pmart.payment.application.ReconciliationService;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 PaymentsApi(내부: orchestration·claim → payment). 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class PaymentsController implements PaymentsApi {

  private final PaymentStartService starts;
  private final ReconciliationService reconciliation;
  private final PaymentStore store;
  private final PaymentLedger ledger;

  public PaymentsController(
      PaymentStartService starts,
      ReconciliationService reconciliation,
      PaymentStore store,
      PaymentLedger ledger) {
    this.starts = starts;
    this.reconciliation = reconciliation;
    this.store = store;
    this.ledger = ledger;
  }

  @Override
  public ResponseEntity<StartPaymentResponse> startPayment(
      String idempotencyKey,
      String xSourceSystem,
      StartPaymentRequest body,
      @Nullable String traceparent) {
    PaymentStartService.Started s =
        starts.start(
            idempotencyKey,
            xSourceSystem,
            new PaymentStartService.Start(
                body.getOrderId(),
                body.getDraftId(),
                body.getSagaId(),
                body.getAmount(),
                body.getCurrency().getValue(),
                body.getCustomerRef(),
                body.getOrderName(),
                Boolean.TRUE.equals(body.getRequiresAdultVerification()),
                body.getResourceExpiresAt().toInstant(),
                body.getPriceValidUntil() == null ? null : body.getPriceValidUntil().toInstant()));
    Attempt a = s.attempt();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new StartPaymentResponse()
                .paymentAttemptId(a.id())
                .state(PaymentState.fromValue(a.stage().state()))
                .orderId(a.orderRef())
                .amount(PaymentMapping.money(a.amount()))
                .checkout(
                    new StartPaymentResponseCheckout()
                        .provider(StartPaymentResponseCheckout.ProviderEnum.TOSS_PAYMENTS)
                        .pgOrderId(a.id())
                        .clientKey(s.clientKey())
                        .expiresAt(PaymentMapping.odt(a.resourceExpiresAt()))));
  }

  @Override
  public ResponseEntity<Payment> getPayment(String paymentAttemptId) {
    Attempt a =
        store
            .find(paymentAttemptId, false)
            .orElseThrow(() -> PmartException.notFound("PAYMENT_NOT_FOUND", "결제 시도가 없습니다."));
    return ResponseEntity.ok(PaymentMapping.payment(a, store.cancelsOf(a.id()), ledger.totals(a)));
  }

  @Override
  public ResponseEntity<PaymentInquiry> createPaymentInquiry(
      String paymentAttemptId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable CreatePaymentInquiryRequest body) {
    String reason =
        body == null || body.getReason() == null ? "OPERATOR_REQUEST" : body.getReason().getValue();
    return ResponseEntity.ok(
        inquiry(reconciliation.inquire(paymentAttemptId, idempotencyKey, reason)));
  }

  @Override
  public ResponseEntity<PaymentInquiry> getPaymentInquiry(
      String paymentAttemptId, String requestId) {
    return ResponseEntity.ok(
        inquiry(
            store
                .inquiry(paymentAttemptId, requestId)
                .orElseThrow(() -> PmartException.notFound("INQUIRY_NOT_FOUND", "조회 이력이 없습니다."))));
  }

  private PaymentInquiry inquiry(PaymentStore.InquiryRow r) {
    Attempt a = store.find(r.attemptId(), false).orElseThrow();
    return new PaymentInquiry()
        .requestId(r.requestId())
        .paymentAttemptId(r.attemptId())
        .reason(r.reason())
        .inquiredAt(PaymentMapping.odt(r.inquiredAt()))
        .pgStatus(a.tossStatus())
        .pgApprovedAmount(a.totalAmount() == null ? null : PaymentMapping.money(a.totalAmount()))
        .ledgerMatched(a.mismatchField() == null)
        .resultingState(PaymentState.fromValue(a.stage().state()));
  }
}
