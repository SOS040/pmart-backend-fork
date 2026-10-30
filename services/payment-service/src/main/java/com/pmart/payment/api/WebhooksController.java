package com.pmart.payment.api;

import com.pmart.payment.api.generated.WebhooksApi;
import com.pmart.payment.api.generated.model.TossWebhookPayload;
import com.pmart.payment.application.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

/** 계약 WebhooksApi — 토스 콜백. 서명은 원문 본문으로 검증한다({@link WebhookRawBodyFilter}). 접수되면 200. */
@RestController
public class WebhooksController implements WebhooksApi {

  private final WebhookService webhooks;

  public WebhooksController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @Override
  public ResponseEntity<Void> receiveTossWebhook(String tossSignature, TossWebhookPayload body) {
    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    ContentCachingRequestWrapper cached =
        WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
    byte[] raw = cached == null ? new byte[0] : cached.getContentAsByteArray();
    webhooks.receive(
        raw,
        tossSignature,
        body.getEventType(),
        body.getData() == null ? null : body.getData().getOrderId());
    return ResponseEntity.ok().build();
  }
}
