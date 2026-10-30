package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.InitialStockLoadsApi;
import com.pmart.mfc.inbound.api.generated.model.ListInitialStockLoads200Response;
import com.pmart.mfc.inbound.application.InitialStockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InitialStockLoadsApi — 운영자가 INITIAL_STOCK 승인 ID 로 반입 결과를 확인한다(LAN 시드 대기에도 쓴다). */
@RestController
public class InitialStockLoadsController implements InitialStockLoadsApi {

  private final InitialStockService loads;

  public InitialStockLoadsController(InitialStockService loads) {
    this.loads = loads;
  }

  @Override
  public ResponseEntity<ListInitialStockLoads200Response> listInitialStockLoads(String approvalId) {
    var out = new ListInitialStockLoads200Response();
    out.setItems(loads.byApproval(approvalId).stream().map(InitialStockLoads::dto).toList());
    return ResponseEntity.ok(out);
  }
}
