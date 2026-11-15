package com.pmart.order.orchestration.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.api.generated.SagasApi;
import com.pmart.order.orchestration.api.generated.model.CompensationLogEntry;
import com.pmart.order.orchestration.api.generated.model.CreateManualAction202Response;
import com.pmart.order.orchestration.api.generated.model.ListSagas200Response;
import com.pmart.order.orchestration.api.generated.model.ListSagas200ResponsePage;
import com.pmart.order.orchestration.api.generated.model.ListStuckSagas200Response;
import com.pmart.order.orchestration.api.generated.model.ManualAction;
import com.pmart.order.orchestration.api.generated.model.ManualActionRequest;
import com.pmart.order.orchestration.api.generated.model.ManualActionType;
import com.pmart.order.orchestration.api.generated.model.Saga;
import com.pmart.order.orchestration.api.generated.model.SagaLastError;
import com.pmart.order.orchestration.api.generated.model.SagaPayment;
import com.pmart.order.orchestration.api.generated.model.SagaState;
import com.pmart.order.orchestration.api.generated.model.SagaStep;
import com.pmart.order.orchestration.api.generated.model.SagaSummary;
import com.pmart.order.orchestration.api.generated.model.SagaTimers;
import com.pmart.order.orchestration.api.generated.model.SagaType;
import com.pmart.order.orchestration.api.generated.model.StepName;
import com.pmart.order.orchestration.api.generated.model.StepState;
import com.pmart.order.orchestration.api.generated.model.StuckSaga;
import com.pmart.order.orchestration.application.CheckoutSagaService;
import com.pmart.order.orchestration.application.FulfillmentSagaService;
import com.pmart.order.orchestration.application.ManualActionService;
import com.pmart.order.orchestration.infrastructure.FulfillmentStore;
import com.pmart.order.orchestration.infrastructure.ManualActionStore;
import com.pmart.order.orchestration.infrastructure.SagaStore.SagaRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 사가 조회(진행 사본) — S1 결제 진입, S2 이행 요청(2026-11-07). 결제·재고·슬롯·작업 성공은 각 정본의 원결과 조회로만 확정한다. 수동 개입(BO-12)은
 * 멈춘 사가 목록·수동 조치(REQUERY_RESULT·MARK_RESOLVED, 2026-11-16 BO-12 — {@link ManualActionService}).
 */
@RestController
public class SagasController implements SagasApi {

  private final CheckoutSagaService sagas;
  private final FulfillmentSagaService fulfillments;

  private final ManualActionService manual;

  SagasController(
      CheckoutSagaService sagas, FulfillmentSagaService fulfillments, ManualActionService manual) {
    this.sagas = sagas;
    this.fulfillments = fulfillments;
    this.manual = manual;
  }

  @Override
  public ResponseEntity<Saga> getSaga(String sagaId) {
    var f = fulfillments.find(sagaId);
    if (f.isPresent()) {
      return ResponseEntity.ok(fulfillment(f.get()));
    }
    SagaRow s =
        sagas
            .find(sagaId)
            .orElseThrow(() -> PmartException.notFound("SAGA_NOT_FOUND", "사가를 찾을 수 없습니다."));
    Saga out =
        new Saga()
            .sagaId(s.sagaId())
            .sagaType(SagaType.S1_CHECKOUT)
            .state(SagaState.fromValue(s.state()))
            .version(s.version())
            .orderId(s.orderId())
            .draftId(s.draftId())
            .originRequestId(s.originRequestId())
            .mfcId(s.mfcId())
            .currentStep(StepName.fromValue(s.currentStep()))
            .timers(
                new SagaTimers()
                    .quoteExpiresAt(time(s.quoteExpiresAt()))
                    .reservationExpiresAt(time(s.resourceExpiresAt()))
                    .paymentPendingHold("UNKNOWN_TRACKING".equals(s.state())))
            .startedAt(time(s.startedAt()))
            .updatedAt(time(s.updatedAt()))
            .finishedAt(time(s.finishedAt()));
    sagas
        .steps(sagaId)
        .forEach(
            st ->
                out.addStepsItem(
                    new SagaStep()
                        .step(StepName.fromValue(st.step()))
                        .state(StepState.fromValue(st.state()))
                        .targetSystem(st.targetSystem())
                        .requestId(st.requestId())
                        .resultId(st.resultId())
                        .attempt(st.attempt())
                        .startedAt(time(st.startedAt()))
                        .finishedAt(time(st.finishedAt()))
                        .timeoutAt(time(st.timeoutAt()))
                        .lastErrorCode(st.lastErrorCode())
                        .lastErrorMessage(st.lastErrorMessage())));
    sagas
        .compensations(sagaId)
        .forEach(
            c ->
                out.addCompensationLogItem(
                    new CompensationLogEntry()
                        .resourceType(
                            CompensationLogEntry.ResourceTypeEnum.fromValue(c.resourceType()))
                        .resourceId(c.resourceId())
                        .ownerSystem(c.ownerSystem())
                        .acquiredAt(time(c.acquiredAt()))
                        .releaseState(
                            CompensationLogEntry.ReleaseStateEnum.fromValue(c.releaseState()))
                        .releaseRequestId(c.releaseRequestId())
                        .releaseVerifiedAt(time(c.releaseVerifiedAt()))));
    JsonNode p = s.payment();
    if (p != null) {
      String expiresAt = text(p, "expiresAt");
      out.payment(
          new SagaPayment()
              .paymentAttemptId(text(p, "paymentAttemptId"))
              .amount(p.path("amount").isMissingNode() ? null : p.path("amount").asInt())
              .orderName(text(p, "orderName"))
              .provider(SagaPayment.ProviderEnum.TOSS_PAYMENTS)
              .pgOrderId(text(p, "pgOrderId"))
              .clientKey(text(p, "clientKey"))
              .checkoutUrl(text(p, "checkoutUrl"))
              .expiresAt(expiresAt == null ? null : OffsetDateTime.parse(expiresAt)));
    }
    if (s.lastErrorCode() != null) {
      out.lastError(
          new SagaLastError()
              .step(s.lastErrorStep() == null ? null : StepName.fromValue(s.lastErrorStep()))
              .code(s.lastErrorCode())
              .message(s.lastErrorMessage()));
    }
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListSagas200Response> listSagas(
      @Nullable String orderId,
      @Nullable String draftId,
      @Nullable String stopRequestId,
      @Nullable SagaType sagaType,
      @Nullable SagaState state,
      @Nullable Integer size,
      @Nullable String cursor) {
    if (orderId == null && draftId == null && stopRequestId == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "orderId·draftId·stopRequestId 중 하나는 있어야 합니다.");
    }
    int n = size == null ? 20 : Math.min(Math.max(size, 1), 100);
    ListSagas200Response out = new ListSagas200Response();
    if (orderId != null
        && draftId == null
        && stopRequestId == null
        && (sagaType == null || sagaType == SagaType.S2_FULFILLMENT)) {
      fulfillments
          .list(orderId, state == null ? null : state.getValue(), n)
          .forEach(
              s ->
                  out.addItemsItem(
                      new SagaSummary()
                          .sagaId(s.sagaId())
                          .sagaType(SagaType.S2_FULFILLMENT)
                          .state(SagaState.fromValue(s.state()))
                          .orderId(s.orderId())
                          .currentStep(StepName.fromValue(s.currentStep()))
                          .startedAt(time(s.startedAt()))
                          .updatedAt(time(s.updatedAt()))));
    }
    if (stopRequestId == null && (sagaType == null || sagaType == SagaType.S1_CHECKOUT)) {
      sagas
          .list(orderId, draftId, state == null ? null : state.getValue(), n)
          .forEach(
              s ->
                  out.addItemsItem(
                      new SagaSummary()
                          .sagaId(s.sagaId())
                          .sagaType(SagaType.S1_CHECKOUT)
                          .state(SagaState.fromValue(s.state()))
                          .orderId(s.orderId())
                          .draftId(s.draftId())
                          .currentStep(StepName.fromValue(s.currentStep()))
                          .startedAt(time(s.startedAt()))
                          .updatedAt(time(s.updatedAt()))));
    }
    return ResponseEntity.ok(out.page(new ListSagas200ResponsePage().size(n)));
  }

  /** S2: 작업 버전마다 REQUEST_FULFILLMENT 단계 하나(attempt = 작업 버전), 대기·자동 취소 단계. */
  private Saga fulfillment(FulfillmentStore.SagaRow s) {
    Saga out =
        new Saga()
            .sagaId(s.sagaId())
            .sagaType(SagaType.S2_FULFILLMENT)
            .state(SagaState.fromValue(s.state()))
            .version(s.version())
            .orderId(s.orderId())
            .mfcId(s.mfcId())
            .currentStep(StepName.fromValue(s.currentStep()))
            .startedAt(time(s.startedAt()))
            .updatedAt(time(s.updatedAt()))
            .finishedAt(time(s.finishedAt()));
    for (FulfillmentStore.AttemptRow a : fulfillments.attempts(s.sagaId())) {
      out.addStepsItem(
          new SagaStep()
              .step(StepName.REQUEST_FULFILLMENT)
              .state(
                  a.decision() == null
                      ? StepState.UNKNOWN
                      : "ACCEPTED".equals(a.decision()) ? StepState.SUCCEEDED : StepState.FAILED)
              .targetSystem("mfc-fulfillment-service")
              .requestId(a.requestId())
              .resultId(s.fulfillmentId())
              .attempt(a.workVersion())
              .startedAt(time(a.requestedAt()))
              .finishedAt(time(a.decidedAt()))
              .lastErrorCode(a.rejectReason()));
    }
    if ("RETRY_WAITING".equals(s.state()) || "CONFIG_REQUIRED".equals(s.state())) {
      out.addStepsItem(
          new SagaStep()
              .step(StepName.AWAIT_RECOVERY)
              .state(StepState.IN_PROGRESS)
              .targetSystem("mfc-inventory-service")
              .startedAt(time(s.firstRejectedAt()))
              .timeoutAt(time(s.nextCheckAt()))
              .lastErrorCode("CONFIG_REQUIRED".equals(s.state()) ? "CONFIG_REQUIRED" : null));
    }
    if (s.claimId() != null || FulfillmentRulesView.SYSTEM_CANCEL.equals(s.currentStep())) {
      out.addStepsItem(
          new SagaStep()
              .step(StepName.REQUEST_SYSTEM_CANCEL)
              .state(s.claimId() != null ? StepState.SUCCEEDED : StepState.IN_PROGRESS)
              .targetSystem("claim-service")
              .resultId(s.claimId()));
    }
    if (s.lastErrorCode() != null) {
      out.lastError(
          new SagaLastError()
              .step(s.lastErrorStep() == null ? null : StepName.fromValue(s.lastErrorStep()))
              .code(s.lastErrorCode())
              .message(s.lastErrorMessage()));
    } else if (s.outcome() != null && !"WORK_ACCEPTED".equals(s.outcome())) {
      // 결과 구분(자동 취소 요청·고객 취소로 닫힘)은 lastError 로 보인다 — 계약에 outcome 필드가 없다
      out.lastError(new SagaLastError().code(s.outcome()));
    }
    return out;
  }

  /** 단계 이름 상수(domain 의존을 한 곳에). */
  private static final class FulfillmentRulesView {
    static final String SYSTEM_CANCEL =
        com.pmart.order.orchestration.domain.FulfillmentRules.REQUEST_SYSTEM_CANCEL;
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private static @Nullable String text(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : v;
  }

  // ---------- 수동 개입(BO-12, 0.2.0)

  @Override
  public ResponseEntity<ListStuckSagas200Response> listStuckSagas(
      Integer size, @Nullable String cursor) {
    ManualActionService.Page page = manual.stuck(cursor, size);
    return ResponseEntity.ok(
        new ListStuckSagas200Response()
            .items(
                page.items().stream()
                    .map(
                        r ->
                            new StuckSaga()
                                .sagaId(r.sagaId())
                                .sagaType(r.sagaType())
                                .orderId(r.orderId())
                                .state(StuckSaga.StateEnum.fromValue(r.state()))
                                .currentStep(r.currentStep())
                                .lastErrorStep(r.lastErrorStep())
                                .lastErrorCode(r.lastErrorCode())
                                .lastErrorMessage(r.lastErrorMessage())
                                .updatedAt(time(r.updatedAt()))
                                .allowedActions(
                                    ManualActionService.allowedActions(r).stream()
                                        .map(ManualActionType::fromValue)
                                        .toList()))
                    .toList())
            .page(
                new ListSagas200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size())));
  }

  @Override
  public ResponseEntity<CreateManualAction202Response> createManualAction(
      String sagaId,
      String idempotencyKey,
      String xSourceSystem,
      ManualActionRequest body,
      @Nullable Integer xExpectedVersion) {
    ManualActionStore.ActionRow a =
        manual.act(
            xSourceSystem,
            idempotencyKey,
            sagaId,
            body.getAction().getValue(),
            body.getOperatorId(),
            body.getReason());
    return ResponseEntity.accepted()
        .body(
            new CreateManualAction202Response()
                .status(CreateManualAction202Response.StatusEnum.APPLIED)
                .requestId(a.requestId())
                .resultId(sagaId)
                .nextAction(a.resultSummary()));
  }

  @Override
  public ResponseEntity<ManualAction> getManualAction(String sagaId, String requestId) {
    ManualActionStore.ActionRow a = manual.get(sagaId, requestId);
    return ResponseEntity.ok(
        new ManualAction()
            .requestId(a.requestId())
            .sagaId(a.sagaId())
            .action(ManualActionType.fromValue(a.action()))
            .operatorId(a.operatorId())
            .state(ManualAction.StateEnum.fromValue(a.state()))
            .resultSummary(a.resultSummary())
            .requestedAt(time(a.requestedAt()))
            .finishedAt(time(a.finishedAt())));
  }
}
