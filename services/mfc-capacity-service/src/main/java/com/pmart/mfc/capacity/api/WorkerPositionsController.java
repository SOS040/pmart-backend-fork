package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.WorkerPositionsApi;
import com.pmart.mfc.capacity.api.generated.model.WorkerPositions;
import com.pmart.mfc.capacity.api.generated.model.WorkerPositionsItemsInner;
import com.pmart.mfc.capacity.api.generated.model.WorkerPositionsUnplacedInner;
import com.pmart.mfc.capacity.application.WorkerPositionService;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 센터 도면 위 작업자 위치(0.34.0) — 마지막 스캔 칸의 도면 좌표. */
@RestController
public class WorkerPositionsController implements WorkerPositionsApi {

  private final WorkerPositionService service;

  public WorkerPositionsController(WorkerPositionService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<WorkerPositions> listWorkerPositions(
      String mfcId, @Nullable Integer sinceMinutes) {
    var r = service.list(mfcId, sinceMinutes);
    var out = new WorkerPositions();
    out.setMfcId(r.mfcId());
    out.setAsOf(r.asOf().atOffset(ZoneOffset.UTC));
    out.setSince(r.since().atOffset(ZoneOffset.UTC));
    out.setItems(
        r.items().stream()
            .map(
                i -> {
                  var x = new WorkerPositionsItemsInner();
                  x.setWorkerId(i.workerId());
                  x.setxM(BigDecimal.valueOf(i.position().xM()));
                  x.setzM(BigDecimal.valueOf(i.position().zM()));
                  x.setSource(WorkerPositionsItemsInner.SourceEnum.SCAN);
                  x.setLocationCode(i.locationCode());
                  x.setActivityType(i.activityType());
                  x.setAt(i.at().atOffset(ZoneOffset.UTC));
                  return x;
                })
            .toList());
    out.setUnplaced(
        r.unplaced().stream()
            .map(
                u -> {
                  var x = new WorkerPositionsUnplacedInner();
                  x.setWorkerId(u.workerId());
                  x.setLocationCode(u.locationCode());
                  x.setActivityType(u.activityType());
                  x.setAt(u.at().atOffset(ZoneOffset.UTC));
                  x.setReason(WorkerPositionsUnplacedInner.ReasonEnum.fromValue(u.reason()));
                  return x;
                })
            .toList());
    out.setUnavailableSources(new ArrayList<>(r.unavailableSources()));
    return ResponseEntity.ok(out);
  }
}
