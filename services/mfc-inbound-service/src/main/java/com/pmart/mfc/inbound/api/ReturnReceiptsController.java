package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.ReturnReceiptsApi;
import com.pmart.mfc.inbound.api.generated.model.GetReturnReceipt200Response;
import com.pmart.mfc.inbound.api.generated.model.GetReturnReceipt200ResponseLinesInner;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.api.generated.model.RecordReturnReceiptRequest;
import com.pmart.mfc.inbound.application.ReturnReceiptService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReturnReceiptsApi — 회수품 반입(IV-10). 업무 규칙은 {@link ReturnReceiptService}. */
@RestController
public class ReturnReceiptsController implements ReturnReceiptsApi {

  private final ReturnReceiptService service;

  public ReturnReceiptsController(ReturnReceiptService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<RecordArrival202Response> recordReturnReceipt(
      String idempotencyKey, String xSourceSystem, RecordReturnReceiptRequest b) {
    var c =
        service.record(
            idempotencyKey,
            xSourceSystem,
            new ReturnReceiptService.Command(
                b.getMfcId(),
                b.getToLocationCode(),
                b.getClaimId(),
                b.getReturnPickupId(),
                b.getOrderId(),
                b.getReceivedAt() == null ? null : b.getReceivedAt().toInstant(),
                b.getLines().stream()
                    .map(
                        l ->
                            new ReturnReceiptService.Line(
                                l.getOrderLineId(),
                                l.getSku(),
                                l.getQty(),
                                l.getSourceLotRef(),
                                l.getExpiryDate(),
                                l.getConditionNote(),
                                l.getEvidenceRef(),
                                null))
                    .toList()));
    var out = new RecordArrival202Response();
    out.setStatus(RecordArrival202Response.StatusEnum.fromValue(c.status().name()));
    out.setRequestId(c.requestId());
    out.setResultId(c.resultId());
    out.setAppliedQty(c.appliedQty());
    out.setVersion(c.version());
    out.setRetryable(c.retryable());
    out.setNextAction(c.nextAction());
    out.setOccurredAt(c.occurredAt().atOffset(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  @Override
  public ResponseEntity<GetReturnReceipt200Response> getReturnReceipt(String returnReceiptId) {
    var r = service.view(returnReceiptId);
    var out = new GetReturnReceipt200Response();
    out.setReturnReceiptId(r.returnReceiptId());
    out.setClaimId(r.claimId());
    out.setReturnPickupId(r.returnPickupId());
    out.setMfcId(r.mfcId());
    out.setOrderId(r.orderId());
    out.setToLocationCode(r.toLocation());
    out.setReceivedBy(r.receivedBy());
    out.setRejectReason(r.rejectReason());
    out.setInventoryApplyStatus(
        GetReturnReceipt200Response.InventoryApplyStatusEnum.fromValue(r.state()));
    out.setInventoryCommitId(r.commitId());
    out.setInspectionState(
        GetReturnReceipt200Response.InspectionStateEnum.fromValue(r.inspectionState()));
    out.setReceivedAt(OffsetDateTime.ofInstant(r.receivedAt(), ZoneOffset.UTC));
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var i = new GetReturnReceipt200ResponseLinesInner();
                  i.setOrderLineId(l.orderLineId());
                  i.setSku(l.sku());
                  i.setQty(l.qty());
                  i.setLotId(l.lotId());
                  i.setConditionNote(l.conditionNote());
                  return i;
                })
            .toList());
    out.setPutawayTaskIds(service.putawayTasks(returnReceiptId));
    return ResponseEntity.ok(out);
  }
}
