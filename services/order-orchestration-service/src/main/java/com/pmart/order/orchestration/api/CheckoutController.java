package com.pmart.order.orchestration.api;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.order.orchestration.api.generated.CheckoutApi;
import com.pmart.order.orchestration.api.generated.model.CreateManualAction202Response;
import com.pmart.order.orchestration.api.generated.model.StartCheckoutSagaRequest;
import com.pmart.order.orchestration.api.generated.model.StartCheckoutSagaRequestArrivalWindow;
import com.pmart.order.orchestration.api.generated.model.StartCheckoutSagaRequestDemand;
import com.pmart.order.orchestration.api.generated.model.StartCheckoutSagaRequestLinesInner;
import com.pmart.order.orchestration.application.CheckoutSagaService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** S1 시작(order → orchestration). 202 는 "사가 시작"이지 재고·슬롯·결제 성공이 아니다. */
@RestController
public class CheckoutController implements CheckoutApi {

  private final CheckoutSagaService sagas;

  CheckoutController(CheckoutSagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  public ResponseEntity<CreateManualAction202Response> startCheckoutSaga(
      String idempotencyKey,
      String xSourceSystem,
      StartCheckoutSagaRequest req,
      @Nullable String traceparent) {
    StartCheckoutSagaRequestArrivalWindow w = req.getArrivalWindow();
    List<Map<String, Object>> lines =
        req.getLines().stream().map(CheckoutController::line).toList();
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    String sagaId =
        sagas.start(
            idempotencyKey,
            xSourceSystem,
            actor,
            new CheckoutSagaService.Start(
                req.getDraftId(),
                req.getOrderId(),
                req.getPurchaseIntentId(),
                req.getCustomerRef(),
                req.getOrderName(),
                req.getAcceptedQuoteVersion(),
                req.getQuoteExpiresAt().toInstant(),
                req.getMfcId(),
                req.getDeliveryMode().getValue(),
                w == null ? null : w.getDate(),
                w == null ? null : w.getStart(),
                w == null ? null : w.getEnd(),
                req.getAmount(),
                Boolean.TRUE.equals(req.getContainsAlcohol()),
                lines,
                demand(req.getDemand(), req.getLines())));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new CreateManualAction202Response()
                .status(CreateManualAction202Response.StatusEnum.ACCEPTED)
                .requestId(idempotencyKey)
                .resultId(sagaId)
                .nextAction("QUERY_RESULT")
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private static Map<String, Object> line(StartCheckoutSagaRequestLinesInner l) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderLineId", l.getOrderLineId());
    m.put("sku", l.getSku());
    m.put("quantity", l.getQuantity());
    return m;
  }

  /** 여력 환산 입력. 없으면 행·수량으로 채운다. */
  private static Map<String, Object> demand(
      @Nullable StartCheckoutSagaRequestDemand d, List<StartCheckoutSagaRequestLinesInner> lines) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("lineCount", d != null && d.getLineCount() != null ? d.getLineCount() : lines.size());
    m.put(
        "itemCount",
        d != null && d.getItemCount() != null
            ? d.getItemCount()
            : lines.stream().mapToInt(StartCheckoutSagaRequestLinesInner::getQuantity).sum());
    if (d != null && d.getWeightG() != null) {
      m.put("weightG", d.getWeightG());
    }
    if (d != null && d.getPlannedShipments() != null) {
      m.put("plannedShipments", d.getPlannedShipments());
    }
    return m;
  }
}
