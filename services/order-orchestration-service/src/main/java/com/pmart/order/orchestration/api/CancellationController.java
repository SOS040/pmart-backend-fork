package com.pmart.order.orchestration.api;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.order.orchestration.api.generated.CancellationApi;
import com.pmart.order.orchestration.api.generated.model.CreateManualAction202Response;
import com.pmart.order.orchestration.api.generated.model.StartCancellationSagaRequest;
import com.pmart.order.orchestration.application.CancellationSagaService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 CancellationApi — S3 시작(claim → orchestration). 사건 CancellationRequested 로도 시작되며 둘 다
 * stopRequestId 하나에 사가 하나다. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class CancellationController implements CancellationApi {

  private final CancellationSagaService sagas;

  public CancellationController(CancellationSagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  public ResponseEntity<CreateManualAction202Response> startCancellationSaga(
      String idempotencyKey,
      String xSourceSystem,
      StartCancellationSagaRequest req,
      @Nullable String traceparent) {
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    String sagaId =
        sagas.start(
            idempotencyKey,
            xSourceSystem,
            actor,
            new CancellationSagaService.Start(
                req.getClaimId(),
                req.getStopRequestId(),
                req.getOrderId(),
                req.getScope().getValue(),
                req.getReason() == null ? null : req.getReason().getValue(),
                req.getLines().stream()
                    .map(
                        l ->
                            Map.<String, Object>of(
                                "orderLineId", l.getOrderLineId(), "qty", l.getQty()))
                    .toList()));
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
