package com.pmart.order.orchestration.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.api.generated.SagasApi;
import com.pmart.order.orchestration.api.generated.model.CompensationLogEntry;
import com.pmart.order.orchestration.api.generated.model.ListSagas200Response;
import com.pmart.order.orchestration.api.generated.model.ListSagas200ResponsePage;
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
import com.pmart.order.orchestration.application.CheckoutSagaService;
import com.pmart.order.orchestration.infrastructure.SagaStore.SagaRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 사가 조회(진행 사본). 결제·재고·슬롯 성공은 각 정본의 원결과 조회로만 확정한다. 수동 개입(BO-12)은 아직 501. */
@RestController
public class SagasController implements SagasApi {

  private final CheckoutSagaService sagas;

  SagasController(CheckoutSagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  public ResponseEntity<Saga> getSaga(String sagaId) {
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

  private static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private static @Nullable String text(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : v;
  }
}
