package com.pmart.order.api;

import com.pmart.order.api.generated.FulfillmentIssuesApi;
import com.pmart.order.api.generated.model.AcknowledgeFulfillmentIssueRequest;
import com.pmart.order.api.generated.model.FulfillmentIssue;
import com.pmart.order.api.generated.model.ListFulfillmentIssues200Response;
import com.pmart.order.application.FulfillmentIssueService;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 FulfillmentIssuesApi — 이행 확인 필요(0.2.0). 업무 규칙은 {@link FulfillmentIssueService}. */
@RestController
public class FulfillmentIssuesController implements FulfillmentIssuesApi {

  private final FulfillmentIssueService service;

  public FulfillmentIssuesController(FulfillmentIssueService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListFulfillmentIssues200Response> listFulfillmentIssues(
      String mfcId, @Nullable String state) {
    var out = new ListFulfillmentIssues200Response();
    out.setItems(
        service.list(mfcId, state).stream().map(FulfillmentIssuesController::dto).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<FulfillmentIssue> acknowledgeFulfillmentIssue(
      String issueId,
      String idempotencyKey,
      String xSourceSystem,
      AcknowledgeFulfillmentIssueRequest body) {
    return ResponseEntity.ok(dto(service.acknowledge(issueId, body.getNote())));
  }

  static FulfillmentIssue dto(FulfillmentIssueService.Issue i) {
    var out = new FulfillmentIssue();
    out.setIssueId(i.issueId());
    out.setOrderId(i.orderId());
    out.setMfcId(i.mfcId());
    out.setReservationId(i.reservationId());
    out.setSku(i.sku());
    out.setLotId(i.lotId());
    out.setBlockedQty(i.blockedQty());
    out.setReason(i.reason());
    out.setState(FulfillmentIssue.StateEnum.fromValue(i.state()));
    out.setNote(i.note());
    out.setAcknowledgedBy(i.acknowledgedBy());
    out.setAcknowledgedAt(
        i.acknowledgedAt() == null ? null : i.acknowledgedAt().atOffset(ZoneOffset.UTC));
    out.setBlockedAt(i.blockedAt().atOffset(ZoneOffset.UTC));
    out.setResolvedAt(i.resolvedAt() == null ? null : i.resolvedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
