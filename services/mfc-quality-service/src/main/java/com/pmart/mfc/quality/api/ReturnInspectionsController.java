package com.pmart.mfc.quality.api;

import com.pmart.mfc.quality.api.generated.ReturnInspectionsApi;
import com.pmart.mfc.quality.api.generated.model.CreateQualityDecision202Response;
import com.pmart.mfc.quality.api.generated.model.InventoryApplyStatus;
import com.pmart.mfc.quality.api.generated.model.ReturnInspection;
import com.pmart.mfc.quality.api.generated.model.ReturnInspectionRequest;
import com.pmart.mfc.quality.api.generated.model.ReturnInspectionRequestLinesInner;
import com.pmart.mfc.quality.application.ReturnInspectionService;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReturnInspectionsApi — 회수품 검수(IV-10). 업무 규칙은 {@link ReturnInspectionService}. */
@RestController
public class ReturnInspectionsController implements ReturnInspectionsApi {

  private final ReturnInspectionService service;

  public ReturnInspectionsController(ReturnInspectionService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<CreateQualityDecision202Response> createReturnInspection(
      String idempotencyKey, String xSourceSystem, ReturnInspectionRequest b) {
    var c =
        service.inspect(
            idempotencyKey,
            xSourceSystem,
            new ReturnInspectionService.Command(
                b.getMfcId(),
                b.getClaimId(),
                b.getReturnReceiptId(),
                b.getInspectedBy(),
                b.getLines().stream()
                    .map(
                        l ->
                            new ReturnInspectionService.ILine(
                                l.getOrderLineId(),
                                l.getSku(),
                                l.getLotId(),
                                null,
                                l.getQty(),
                                l.getVerdict().getValue(),
                                l.getTemperatureZone().getValue(),
                                Boolean.TRUE.equals(l.getFresh()),
                                Boolean.TRUE.equals(l.getOpened()),
                                l.getDisposeReason() == null
                                    ? null
                                    : l.getDisposeReason().getValue(),
                                l.getEvidenceRef()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dtos.command(c));
  }

  @Override
  public ResponseEntity<ReturnInspection> getReturnInspection(String returnInspectionId) {
    var r = service.view(returnInspectionId);
    var out = new ReturnInspection();
    out.setReturnInspectionId(r.returnInspectionId());
    out.setMfcId(r.mfcId());
    out.setClaimId(r.claimId());
    out.setReturnReceiptId(r.returnReceiptId());
    out.setInspectedBy(r.inspectedBy());
    out.setVersion(r.version());
    out.setResaleQty(r.resaleQty());
    out.setDisposeQty(r.disposeQty());
    out.setInventoryApplyStatus(InventoryApplyStatus.fromValue(r.applyStatus()));
    out.setInventoryCommitId(r.inventoryCommitId());
    out.setDisposalApprovalId(r.disposalApprovalId());
    out.setInspectedAt(r.inspectedAt().atOffset(ZoneOffset.UTC));
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var i = new ReturnInspectionRequestLinesInner();
                  i.setOrderLineId(l.orderLineId());
                  i.setSku(l.sku());
                  i.setLotId(l.lotId());
                  i.setQty(l.qty());
                  i.setVerdict(
                      ReturnInspectionRequestLinesInner.VerdictEnum.fromValue(l.verdict()));
                  i.setTemperatureZone(
                      ReturnInspectionRequestLinesInner.TemperatureZoneEnum.fromValue(
                          l.temperatureZone()));
                  i.setFresh(l.fresh());
                  i.setOpened(l.opened());
                  if (l.disposeReason() != null) {
                    i.setDisposeReason(
                        ReturnInspectionRequestLinesInner.DisposeReasonEnum.fromValue(
                            l.disposeReason()));
                  }
                  i.setEvidenceRef(l.evidenceRef());
                  return i;
                })
            .toList());
    return ResponseEntity.ok(out);
  }
}
