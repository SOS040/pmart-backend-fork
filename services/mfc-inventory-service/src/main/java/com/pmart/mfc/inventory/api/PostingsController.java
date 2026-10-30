package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.PostingsApi;
import com.pmart.mfc.inventory.api.generated.model.ConfirmReceiptRequest;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;
import com.pmart.mfc.inventory.application.ReceiptService;
import com.pmart.mfc.inventory.application.ReceiptService.ReceiptCommand;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PostingsApi. 수령 전기(ConfirmReceipt)만 구현했다. 작업 실행·인계·품질·조정은 인터페이스 default 의 501. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class PostingsController implements PostingsApi {

  private final ReceiptService receipts;

  public PostingsController(ReceiptService receipts) {
    this.receipts = receipts;
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
                        l.getSku(), l.getNormalQty(), l.getQuarantinedQty(), l.getPendingQty()))
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
}
