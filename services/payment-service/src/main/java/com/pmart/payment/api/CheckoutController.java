package com.pmart.payment.api;

import com.pmart.payment.api.generated.CheckoutApi;
import com.pmart.payment.api.generated.model.CheckoutPayment;
import com.pmart.payment.api.generated.model.ConfirmPaymentRequest;
import com.pmart.payment.api.generated.model.PaymentWidgetConfig;
import com.pmart.payment.api.generated.model.ReportAuthorizationFailureRequest;
import com.pmart.payment.application.CheckoutService;
import com.pmart.payment.application.WidgetConfigService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CheckoutApi — 고객 앱 승인 요청·인증 실패 보고·상태 조회(본인 것만)·결제위젯 설정. 변환만 한다. */
@RestController
public class CheckoutController implements CheckoutApi {

  private final CheckoutService checkout;
  private final WidgetConfigService widgetConfig;

  public CheckoutController(CheckoutService checkout, WidgetConfigService widgetConfig) {
    this.checkout = checkout;
    this.widgetConfig = widgetConfig;
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

  /** 앱은 로그인 세션마다 한 번 받아 재사용한다(BR-34) — 개인 값이 있어 private 캐시만. */
  @Override
  public ResponseEntity<PaymentWidgetConfig> getPaymentWidgetConfig() {
    WidgetConfigService.WidgetConfig c = widgetConfig.current();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .body(
            new PaymentWidgetConfig()
                .provider(PaymentWidgetConfig.ProviderEnum.TOSS_PAYMENTS)
                .clientKey(c.clientKey())
                .customerKey(c.customerKey()));
  }
}
