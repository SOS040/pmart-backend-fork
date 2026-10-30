package com.pmart.pricing.api;

import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.CommandResponse;
import com.pmart.pricing.api.generated.PriceVersionsApi;
import com.pmart.pricing.api.generated.model.ApplyPriceVersion202Response;
import com.pmart.pricing.api.generated.model.ApplyPriceVersionRequest;
import com.pmart.pricing.api.generated.model.GetPriceVersionApplication200Response;
import com.pmart.pricing.api.generated.model.GetPriceVersionApplication200ResponseItemsInner;
import com.pmart.pricing.application.PriceApplicationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 PriceVersionsApi — 승인된 가격 변경의 반영 확인. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class PriceVersionsController implements PriceVersionsApi {

  private final PriceApplicationService applications;

  public PriceVersionsController(PriceApplicationService applications) {
    this.applications = applications;
  }

  @Override
  public ResponseEntity<ApplyPriceVersion202Response> applyPriceVersion(
      String idempotencyKey, String xSourceSystem, ApplyPriceVersionRequest body) {
    List<PriceApplicationService.Item> items =
        body.getItems().stream()
            .map(
                i ->
                    new PriceApplicationService.Item(
                        i.getSku(), i.getBasePrice(), i.getEffectiveFrom().toInstant()))
            .toList();
    Outcome<CommandResponse> out =
        applications.apply(idempotencyKey, xSourceSystem, body.getApprovalId(), items, body);
    CommandResponse r = out.value();
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new ApplyPriceVersion202Response()
                .status(ApplyPriceVersion202Response.StatusEnum.fromValue(r.status().name()))
                .requestId(r.requestId())
                .resultId(r.resultId())
                .retryable(r.retryable())
                .nextAction(r.nextAction())
                .occurredAt(PricingMapping.odt(r.occurredAt())));
  }

  @Override
  public ResponseEntity<GetPriceVersionApplication200Response> getPriceVersionApplication(
      String requestId) {
    PriceApplicationService.Result r = applications.result(requestId);
    return ResponseEntity.ok(
        new GetPriceVersionApplication200Response()
            .requestId(r.requestId())
            .status(GetPriceVersionApplication200Response.StatusEnum.fromValue(r.status().name()))
            .approvalId(r.approvalId())
            .items(
                r.items().stream()
                    .map(
                        i ->
                            new GetPriceVersionApplication200ResponseItemsInner()
                                .sku(i.sku())
                                .priceVersion(i.priceVersion())
                                .state(
                                    GetPriceVersionApplication200ResponseItemsInner.StateEnum
                                        .fromValue(i.state())))
                    .toList()));
  }
}
