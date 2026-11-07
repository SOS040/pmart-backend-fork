package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.SupplyRequestsApi;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequest;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestBody;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestBodyLinesInner;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestState;
import com.pmart.mfc.inbound.api.generated.model.WithdrawSupplyRequestRequest;
import com.pmart.mfc.inbound.application.SupplyRequestService;
import com.pmart.mfc.inbound.application.SupplyRequestService.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SupplyRequestsApi — 로컬 공급 요청(WK-14). 업무 규칙은 {@link SupplyRequestService}. */
@RestController
public class SupplyRequestsController implements SupplyRequestsApi {

  private final SupplyRequestService service;

  public SupplyRequestsController(SupplyRequestService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<SupplyRequest> createSupplyRequest(
      String idempotencyKey, String xSourceSystem, SupplyRequestBody body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dto(service.create(idempotencyKey, xSourceSystem, body(body))));
  }

  @Override
  public ResponseEntity<SupplyRequest> getSupplyRequest(String supplyRequestId) {
    return ResponseEntity.ok(dto(service.find(supplyRequestId)));
  }

  @Override
  public ResponseEntity<SupplyRequest> updateSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      SupplyRequestBody body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(dto(service.update(supplyRequestId, xExpectedVersion, body(body))));
  }

  @Override
  public ResponseEntity<RecordArrival202Response> withdrawSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      WithdrawSupplyRequestRequest body,
      @Nullable Integer xExpectedVersion) {
    Row r = service.withdraw(supplyRequestId, body.getReason());
    var out = new RecordArrival202Response();
    out.setStatus(RecordArrival202Response.StatusEnum.APPLIED);
    out.setRequestId(idempotencyKey);
    out.setResultId(r.supplyRequestId());
    out.setVersion(r.version());
    out.setRetryable(false);
    out.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  private static SupplyRequestService.Body body(SupplyRequestBody b) {
    return new SupplyRequestService.Body(
        b.getMfcId(),
        b.getRequestedReadyAt().toInstant(),
        b.getUrgency().getValue(),
        b.getReason(),
        b.getRecommendationRef(),
        b.getLines().stream()
            .map(l -> new SupplyRequestService.Line(l.getSku(), l.getQty()))
            .toList());
  }

  private static SupplyRequest dto(Row r) {
    var out = new SupplyRequest();
    out.setSupplyRequestId(r.supplyRequestId());
    out.setMfcId(r.mfcId());
    out.setVersion(r.version());
    out.setState(SupplyRequestState.fromValue(r.state()));
    out.setRequestedReadyAt(odt(r.requestedReadyAt()));
    out.setUrgency(SupplyRequest.UrgencyEnum.fromValue(r.urgency()));
    out.setReason(r.reason());
    out.setRecommendationRef(r.recommendationRef());
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var x = new SupplyRequestBodyLinesInner();
                  x.setSku(l.sku());
                  x.setQty(l.qty());
                  return x;
                })
            .toList());
    out.setSubmittedBy(r.submittedBy());
    out.setSubmittedAt(odt(r.submittedAt()));
    out.setAcknowledgedAt(r.acknowledgedAt() == null ? null : odt(r.acknowledgedAt()));
    out.setLinkedTrfId(r.linkedTrfId());
    return out;
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
