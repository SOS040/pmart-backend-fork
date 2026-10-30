package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.InternalApi;
import com.pmart.mfc.inbound.api.generated.model.InitialStockLoad;
import com.pmart.mfc.inbound.application.InitialStockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi. 초기 보유 반입 결과 조회만 구현했다. 반입 접수(POST /internal/initial-stock-loads)는 INITIAL_STOCK 승인
 * 이벤트로 들어오므로 인터페이스 default 의 501 이다(계약 설명 참조).
 */
@RestController
public class InternalController implements InternalApi {

  private final InitialStockService loads;

  public InternalController(InitialStockService loads) {
    this.loads = loads;
  }

  @Override
  public ResponseEntity<InitialStockLoad> getInitialStockLoad(String receiptId) {
    return ResponseEntity.ok(InitialStockLoads.dto(loads.get(receiptId)));
  }
}
