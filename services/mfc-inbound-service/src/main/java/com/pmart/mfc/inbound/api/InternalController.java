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
  private final com.pmart.mfc.inbound.application.AutoSupply autoSupply;

  public InternalController(
      InitialStockService loads, com.pmart.mfc.inbound.application.AutoSupply autoSupply) {
    this.loads = loads;
    this.autoSupply = autoSupply;
  }

  /** (0.23.0, ADR-0091) 자동 발주 초안 바로 실행. */
  @Override
  public ResponseEntity<com.pmart.mfc.inbound.api.generated.model.RunAutoSupply200Response>
      runAutoSupply(
          com.pmart.mfc.inbound.api.generated.model.@org.jspecify.annotations.Nullable
              RunAutoSupplyRequest
              body) {
    String mfc = body == null ? null : body.getMfcId();
    java.util.List<String> ids;
    if (mfc == null || mfc.isBlank()) {
      ids = autoSupply.draftAll();
    } else {
      String id = autoSupply.draft(mfc);
      ids = id == null ? java.util.List.of() : java.util.List.of(id);
    }
    return ResponseEntity.ok(
        new com.pmart.mfc.inbound.api.generated.model.RunAutoSupply200Response()
            .supplyRequestIds(ids));
  }

  @Override
  public ResponseEntity<InitialStockLoad> getInitialStockLoad(String receiptId) {
    return ResponseEntity.ok(InitialStockLoads.dto(loads.get(receiptId)));
  }
}
