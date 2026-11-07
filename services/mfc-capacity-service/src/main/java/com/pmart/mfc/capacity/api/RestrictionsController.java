package com.pmart.mfc.capacity.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.mfc.capacity.api.generated.RestrictionsApi;
import com.pmart.mfc.capacity.api.generated.model.ApplyOperationRestrictionRequest;
import com.pmart.mfc.capacity.api.generated.model.HoldCapacity201Response;
import com.pmart.mfc.capacity.api.generated.model.ListOperationRestrictions200Response;
import com.pmart.mfc.capacity.api.generated.model.OperationRestriction;
import com.pmart.mfc.capacity.application.RestrictionService;
import com.pmart.mfc.capacity.infrastructure.RestrictionStore.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 RestrictionsApi — 운영 제한(BO-08, 0.3.0). 업무 규칙은 {@link RestrictionService}·{@code
 * RestrictionRules}.
 */
@RestController
public class RestrictionsController implements RestrictionsApi {

  private final RestrictionService service;

  public RestrictionsController(RestrictionService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> applyOperationRestriction(
      String idempotencyKey, String xSourceSystem, ApplyOperationRestrictionRequest body) {
    var r =
        service.apply(
            idempotencyKey,
            xSourceSystem,
            new RestrictionService.ApplyCommand(
                body.getMfcId(),
                body.getRestrictionType().getValue(),
                body.getApprovalRef(),
                body.getReason(),
                body.getEffectiveFrom().toInstant(),
                body.getEffectiveTo() == null ? null : body.getEffectiveTo().toInstant(),
                body.getReducedCapacityUnits()));
    return ResponseEntity.status(HttpStatus.CREATED).body(command(r));
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> liftOperationRestriction(
      String restrictionId,
      String approvalRef,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(command(service.lift(idempotencyKey, restrictionId, approvalRef)));
  }

  @Override
  public ResponseEntity<OperationRestriction> getOperationRestriction(String restrictionId) {
    return ResponseEntity.ok(dto(service.find(restrictionId)));
  }

  @Override
  public ResponseEntity<ListOperationRestrictions200Response> listOperationRestrictions(
      String mfcId, @Nullable Boolean activeOnly) {
    return ResponseEntity.ok(
        new ListOperationRestrictions200Response()
            .items(
                service.list(mfcId, activeOnly == null || activeOnly).stream()
                    .map(this::dto)
                    .toList()));
  }

  private OperationRestriction dto(Row r) {
    return new OperationRestriction()
        .restrictionId(r.restrictionId())
        .mfcId(r.mfcId())
        .restrictionType(OperationRestriction.RestrictionTypeEnum.fromValue(r.type()))
        .state(OperationRestriction.StateEnum.fromValue(r.state()))
        .version(r.version())
        .approvalRef(r.approvalRef())
        .liftApprovalRef(r.liftApprovalRef())
        .reason(r.reason())
        .effectiveFrom(odt(r.effectiveFrom()))
        .effectiveTo(r.effectiveTo() == null ? null : odt(r.effectiveTo()))
        .reducedCapacityUnits(r.reducedCapacityUnits())
        .affectedOrderIds(service.affected(r));
  }

  private static HoldCapacity201Response command(CommandResponse r) {
    return new HoldCapacity201Response()
        .status(HoldCapacity201Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.resultId())
        .appliedQty(r.appliedQty())
        .version(r.version())
        .retryable(r.retryable())
        .nextAction(r.nextAction())
        .occurredAt(odt(r.occurredAt()));
  }

  private static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
