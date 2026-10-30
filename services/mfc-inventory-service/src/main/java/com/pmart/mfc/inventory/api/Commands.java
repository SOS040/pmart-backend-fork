package com.pmart.mfc.inventory.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;

/** 공통 CommandResponse → 계약 DTO(생성 이름이 ReserveInventory201Response 인 _common CommandResponse). */
final class Commands {

  private Commands() {}

  static ReserveInventory201Response dto(CommandResponse c) {
    var d = new ReserveInventory201Response();
    d.setStatus(ReserveInventory201Response.StatusEnum.fromValue(c.status().name()));
    d.setRequestId(c.requestId());
    d.setResultId(c.resultId());
    d.setAppliedQty(c.appliedQty());
    d.setVersion(c.version());
    d.setRetryable(c.retryable());
    d.setNextAction(c.nextAction());
    d.setOccurredAt(SkuHandlingsController.time(c.occurredAt()));
    return d;
  }
}
