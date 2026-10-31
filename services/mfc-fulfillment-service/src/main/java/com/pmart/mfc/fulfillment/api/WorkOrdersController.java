package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.WorkOrdersApi;
import com.pmart.mfc.fulfillment.api.generated.model.StopOrderWorkRequest;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResult;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResultNotStartedLinesInner;
import com.pmart.mfc.fulfillment.application.WorkStopService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 WorkOrdersApi — 주문 단위 작업 중지(S3-a)만 구현. 작업 지시 생성·조회·작업 지시 단위 중지는 슬라이스 6 전이라 인터페이스 default 가 501
 * 을 돌려준다. 업무 규칙은 application 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class WorkOrdersController implements WorkOrdersApi {

  private final WorkStopService stops;

  public WorkOrdersController(WorkStopService stops) {
    this.stops = stops;
  }

  @Override
  public ResponseEntity<StopRequestResult> stopOrderWork(
      String idempotencyKey, String xSourceSystem, StopOrderWorkRequest body) {
    WorkStopService.Verdict v =
        stops.stop(
            idempotencyKey,
            xSourceSystem,
            new WorkStopService.StopCommand(
                body.getStopRequestId(),
                body.getOrderId(),
                body.getClaimId(),
                body.getScope().getValue(),
                body.getLines().stream()
                    .map(l -> new WorkStopService.Line(l.getOrderLineId(), l.getQty()))
                    .toList()));
    return ResponseEntity.ok(result(v));
  }

  @Override
  public ResponseEntity<StopRequestResult> getOrderStopRequest(String stopRequestId) {
    return ResponseEntity.ok(result(stops.get(stopRequestId)));
  }

  private static StopRequestResult result(WorkStopService.Verdict v) {
    return new StopRequestResult()
        .stopRequestId(v.stopRequestId())
        .orderId(v.orderId())
        .state(StopRequestResult.StateEnum.fromValue(v.state()))
        .notStartedLines(
            v.notStartedLines().stream()
                .map(
                    l ->
                        new StopRequestResultNotStartedLinesInner()
                            .orderLineId(l.orderLineId())
                            .qty(l.qty()))
                .toList())
        .packages(List.of())
        .resolvedAt(OffsetDateTime.ofInstant(v.resolvedAt(), ZoneOffset.UTC));
  }
}
