package com.pmart.payment.api;

import com.pmart.payment.api.generated.CheckoutApi;
import com.pmart.payment.api.generated.model.CheckoutPayment;
import com.pmart.payment.api.generated.model.ConfirmPaymentRequest;
import com.pmart.payment.api.generated.model.ReportAuthorizationFailureRequest;
import com.pmart.payment.application.CheckoutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CheckoutApi — 고객 앱 승인 요청·인증 실패 보고·상태 조회(본인 것만). 변환만 한다. */
@RestController
public class CheckoutController implements CheckoutApi {

  private final CheckoutService checkout;

  public CheckoutController(CheckoutService checkout) {
    this.checkout = checkout;
  }

  @Override
  public ResponseEntity<CheckoutPayment> confirmPayment(
      String idempotencyKey, String xSourceSystem, ConfirmPaymentRequest body) {
    return ResponseEntity.ok(
        PaymentMapping.checkout(
            checkout.confirm(body.getOrderId(), body.getPaymentKey(), body.getAmount())));
  }

  @Override
  public ResponseEntity<CheckoutPayment> reportAuthorizationFailure(
      String idempotencyKey, String xSourceSystem, ReportAuthorizationFailureRequest body) {
    return ResponseEntity.ok(
        PaymentMapping.checkout(
            checkout.reportFailure(
                body.getOrderId(),
                body.getErrorCode(),
                body.getErrorMessage() == null ? "" : body.getErrorMessage())));
  }

  @Override
  public ResponseEntity<CheckoutPayment> getCheckoutPayment(String orderId) {
    return ResponseEntity.ok(PaymentMapping.checkout(checkout.get(orderId)));
  }
}
