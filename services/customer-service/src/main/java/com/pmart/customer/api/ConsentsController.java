package com.pmart.customer.api;

import com.pmart.customer.api.generated.ConsentsApi;
import com.pmart.customer.api.generated.model.ChangeMyConsentsRequest;
import com.pmart.customer.api.generated.model.ChangeMyProfile200Response;
import com.pmart.customer.api.generated.model.ListMyConsents200Response;
import com.pmart.customer.application.ConsentService;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ConsentsApi — 본인 약관 동의 조회·변경(CU-1, 2026-11-17). 업무 규칙은 {@link ConsentService}. */
@RestController
public class ConsentsController implements ConsentsApi {

  private final ConsentService consents;

  public ConsentsController(ConsentService consents) {
    this.consents = consents;
  }

  @Override
  public ResponseEntity<ListMyConsents200Response> listMyConsents() {
    ConsentService.Overview o = consents.overview(CustomerMapping.currentCustomer());
    return ResponseEntity.ok(
        new ListMyConsents200Response()
            .items(o.items().stream().map(CustomerMapping::consent).toList())
            .requiredConsentOutdated(o.status().requiredOutdated()));
  }

  @Override
  public ResponseEntity<ChangeMyProfile200Response> changeMyConsents(
      String idempotencyKey, String xSourceSystem, ChangeMyConsentsRequest body) {
    return ResponseEntity.ok(
        response(
            consents.change(
                CustomerMapping.currentCustomer(),
                idempotencyKey,
                xSourceSystem,
                CustomerMapping.changes(body.getChanges()),
                ConsentService.CHANNEL_APP,
                ConsentService.OPERATION_ME)));
  }

  @Override
  public ResponseEntity<ChangeMyProfile200Response> getMyConsentChange(String requestId) {
    return ResponseEntity.ok(
        response(consents.result(CustomerMapping.currentCustomer(), requestId)));
  }

  private static ChangeMyProfile200Response response(ConsentService.Result r) {
    return new ChangeMyProfile200Response()
        .status(ChangeMyProfile200Response.StatusEnum.APPLIED)
        .requestId(r.requestId())
        .occurredAt(CustomerMapping.time(Instant.parse(r.occurredAt())));
  }
}
