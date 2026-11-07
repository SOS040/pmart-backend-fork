package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.PostingsApi;
import com.pmart.mfc.inventory.api.generated.model.CommitTaskExecutionRequest;
import com.pmart.mfc.inventory.api.generated.model.ConfirmReceiptRequest;
import com.pmart.mfc.inventory.api.generated.model.ReceiptResult;
import com.pmart.mfc.inventory.api.generated.model.ReceiptResultLinesInner;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;
import com.pmart.mfc.inventory.application.ReceiptService;
import com.pmart.mfc.inventory.application.ReceiptService.ReceiptCommand;
import com.pmart.mfc.inventory.application.TaskExecutionService;
import com.pmart.mfc.inventory.application.TaskExecutionService.ExecCommand;
import com.pmart.mfc.inventory.application.TaskExecutionService.ExecLine;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PostingsApi. 수령 전기(ConfirmReceipt)·수령 결과 조회·작업 실행(INSPECT·PUTAWAY)을 구현했다. 인계·품질·조정은 인터페이스
 * default 의 501. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class PostingsController implements PostingsApi {

  private final ReceiptService receipts;
  private final TaskExecutionService executions;

  public PostingsController(ReceiptService receipts, TaskExecutionService executions) {
    this.receipts = receipts;
    this.executions = executions;
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> confirmReceipt(
      String idempotencyKey,
      String xSourceSystem,
      ConfirmReceiptRequest body,
      @Nullable String traceparent) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new ReceiptLine(
                        l.getSku(),
                        l.getSourceLotRef(),
                        l.getExpiryDate(),
                        l.getToLocationCode(),
                        l.getNormalQty(),
                        l.getQuarantinedQty(),
                        l.getPendingQty()))
            .toList();
    var r =
        receipts.confirm(
            idempotencyKey,
            xSourceSystem,
            new ReceiptCommand(
                body.getReceiptId(),
                body.getTrfId(),
                body.getDepartureId(),
                body.getMfcId(),
                body.getReceivedAt() == null ? null : body.getReceivedAt().toInstant(),
                lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  @Override
  public ResponseEntity<ReceiptResult> getReceipt(String receiptId) {
    var v = receipts.result(receiptId);
    var out = new ReceiptResult();
    out.setReceiptId(receiptId);
    out.setCommitId(v.commitId());
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var d = new ReceiptResultLinesInner();
                  d.setLineNo(l.lineNo());
                  d.setSku(l.sku());
                  d.setLotId(l.lotId());
                  d.setLocationCode(l.locationCode());
                  d.setNormalQty(l.normalQty());
                  d.setQuarantinedQty(l.quarantinedQty());
                  d.setPendingQty(l.pendingQty());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> commitTaskExecution(
      String idempotencyKey,
      String xSourceSystem,
      CommitTaskExecutionRequest body,
      @Nullable Integer xExpectedVersion) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new ExecLine(
                        l.getSku(),
                        l.getLotId(),
                        l.getQty(),
                        l.getResultQuality() == null
                            ? null
                            : Quality.valueOf(l.getResultQuality().getValue())))
            .toList();
    var r =
        executions.commit(
            idempotencyKey,
            xSourceSystem,
            new ExecCommand(
                TaskExecutionService.Type.valueOf(body.getExecutionType().getValue()),
                body.getMfcId(),
                body.getTaskId(),
                body.getExecutionId(),
                body.getFromLocation(),
                body.getToLocation(),
                lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }
}
