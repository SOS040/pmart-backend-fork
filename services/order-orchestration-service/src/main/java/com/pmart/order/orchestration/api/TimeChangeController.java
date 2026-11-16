package com.pmart.order.orchestration.api;

import com.pmart.order.orchestration.api.generated.TimeChangeApi;
import com.pmart.order.orchestration.api.generated.model.CreateManualAction202Response;
import com.pmart.order.orchestration.api.generated.model.StartTimeChangeSagaRequest;
import com.pmart.order.orchestration.api.generated.model.TimeChangeWindow;
import com.pmart.order.orchestration.application.TimeChangeSagaService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** S4 예약 시간 변경 시작(order → orchestration, 2026-11-16). 202 는 "사가 시작"이지 변경 성공이 아니다. */
@RestController
public class TimeChangeController implements TimeChangeApi {

  private final TimeChangeSagaService sagas;

  TimeChangeController(TimeChangeSagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  public ResponseEntity<CreateManualAction202Response> startTimeChangeSaga(
      String idempotencyKey, String xSourceSystem, StartTimeChangeSagaRequest req) {
    TimeChangeWindow from = req.getCurrentArrivalWindow();
    TimeChangeWindow to = req.getNewArrivalWindow();
    String sagaId =
        sagas.start(
            new TimeChangeSagaService.Start(
                req.getOrderId(),
                req.getRequestId(),
                req.getMfcId(),
                from.getDate(),
                from.getStart(),
                from.getEnd(),
                to.getDate(),
                to.getStart(),
                to.getEnd()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new CreateManualAction202Response()
                .status(CreateManualAction202Response.StatusEnum.ACCEPTED)
                .requestId(idempotencyKey)
                .resultId(sagaId)
                .nextAction("QUERY_RESULT")
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC)));
  }
}
