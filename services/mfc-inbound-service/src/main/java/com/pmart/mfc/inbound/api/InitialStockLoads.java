package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.model.InitialStockLoad;
import com.pmart.mfc.inbound.api.generated.model.InitialStockLoadLinesInner;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore.LoadRow;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 반입 기록 → 계약 DTO. */
final class InitialStockLoads {

  private InitialStockLoads() {}

  static InitialStockLoad dto(LoadRow r) {
    var d = new InitialStockLoad();
    d.setReceiptId(r.receiptId());
    d.setApprovalId(r.approvalId());
    d.setMfcId(r.mfcId());
    d.setState(InitialStockLoad.StateEnum.fromValue(r.state()));
    d.setRejectReason(r.rejectReason());
    d.setInventoryCommitId(r.commitId());
    d.setAttempts(r.attempts());
    d.setLastError(r.lastError());
    List<InitialStockLoadLinesInner> lines = new ArrayList<>();
    r.lines()
        .forEach(
            (sku, qty) -> {
              var l = new InitialStockLoadLinesInner();
              l.setSku(sku);
              l.setQty(qty);
              lines.add(l);
            });
    d.setLines(lines);
    d.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    d.setAppliedAt(r.appliedAt() == null ? null : r.appliedAt().atOffset(ZoneOffset.UTC));
    return d;
  }
}
