package com.pmart.cases.api;

import com.pmart.cases.api.generated.IntakesApi;
import com.pmart.cases.api.generated.model.AcceptShiftHandoverRequest;
import com.pmart.cases.api.generated.model.CreateCsIntakeRequest;
import com.pmart.cases.api.generated.model.CreateCsIntakeRequestConsent;
import com.pmart.cases.api.generated.model.CreateShiftHandoverRequest;
import com.pmart.cases.api.generated.model.Intake;
import com.pmart.cases.api.generated.model.IntakeConsent;
import com.pmart.cases.api.generated.model.IntakeIdentityCheck;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListCsIntakes200Response;
import com.pmart.cases.api.generated.model.ListShiftHandovers200Response;
import com.pmart.cases.api.generated.model.ShiftHandover;
import com.pmart.cases.api.generated.model.ShiftHandoverItemsInner;
import com.pmart.cases.api.generated.model.ShiftHandoverTeamSnapshotInner;
import com.pmart.cases.application.CsIntakeService;
import com.pmart.cases.application.ShiftHandoverService;
import com.pmart.cases.infrastructure.CsIntakeStore.IntakeRow;
import com.pmart.cases.infrastructure.ShiftHandoverStore.HandoverRow;
import com.pmart.cases.infrastructure.ShiftHandoverStore.ItemRow;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 IntakesApi — 교대 인수인계(0.9.0, 2026-11-16 BO-27)와 CS 접수(0.12.0, BO-11). 도움요청 접수는 인터페이스 default
 * 501(사용 안 함). 규칙은 {@link ShiftHandoverService}·{@link CsIntakeService}, 여기서는 요청·응답 변환만.
 */
@RestController
public class IntakesController implements IntakesApi {

  private final ShiftHandoverService handovers;
  private final CsIntakeService intakes;

  public IntakesController(ShiftHandoverService handovers, CsIntakeService intakes) {
    this.handovers = handovers;
    this.intakes = intakes;
  }

  @Override
  public ResponseEntity<ShiftHandover> createShiftHandover(
      String idempotencyKey, String xSourceSystem, CreateShiftHandoverRequest body) {
    ShiftHandoverService.View v =
        handovers.create(
            idempotencyKey,
            xSourceSystem,
            new ShiftHandoverService.Create(
                body.getGroup().getValue(),
                body.getCenterId(),
                body.getFromOperatorId(),
                body.getToOperatorId(),
                body.getItems().stream()
                    .map(
                        i ->
                            new ShiftHandoverService.Item(
                                i.getCaseId(),
                                i.getNextAction(),
                                i.getCheckDueAt() == null ? null : i.getCheckDueAt().toInstant()))
                    .toList(),
                body.getNote()));
    return ResponseEntity.created(URI.create("/api/v1/shift-handovers/" + v.row().handoverId()))
        .body(toModel(v));
  }

  @Override
  public ResponseEntity<ShiftHandover> acceptShiftHandover(
      String shiftHandoverId,
      String idempotencyKey,
      String xSourceSystem,
      AcceptShiftHandoverRequest body) {
    List<String> excluded =
        body == null || body.getExcludedCaseIds() == null ? List.of() : body.getExcludedCaseIds();
    return ResponseEntity.ok(
        toModel(handovers.accept(idempotencyKey, xSourceSystem, shiftHandoverId, excluded)));
  }

  @Override
  public ResponseEntity<ShiftHandover> cancelShiftHandover(
      String shiftHandoverId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        toModel(handovers.cancel(idempotencyKey, xSourceSystem, shiftHandoverId)));
  }

  @Override
  public ResponseEntity<ShiftHandover> getShiftHandover(String shiftHandoverId) {
    return ResponseEntity.ok(toModel(handovers.get(shiftHandoverId)));
  }

  @Override
  public ResponseEntity<ListShiftHandovers200Response> listShiftHandovers(
      @Nullable String state, @Nullable Boolean unreceived, Integer size, @Nullable String cursor) {
    ShiftHandoverService.Page page =
        handovers.list(state, Boolean.TRUE.equals(unreceived), size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListShiftHandovers200Response()
            .items(page.items().stream().map(IntakesController::toModel).toList())
            .page(new ListCases200ResponsePage().nextCursor(page.nextCursor())));
  }

  @Override
  public ResponseEntity<Intake> createCsIntake(
      String idempotencyKey, String xSourceSystem, CreateCsIntakeRequest body) {
    CreateCsIntakeRequestConsent c = body.getConsent();
    IntakeRow r =
        intakes.create(
            idempotencyKey,
            xSourceSystem,
            new CsIntakeService.Create(
                body.getChannel().getValue(),
                body.getCategory().getValue(),
                body.getCustomerRef(),
                body.getSubjectRef(),
                body.getSummary(),
                body.getIdentityCheck().getMethod().getValue(),
                body.getIdentityCheck().getNote(),
                body.getCallerRelation().getValue(),
                c == null
                    ? null
                    : new CsIntakeService.Consent(
                        c.getConsentType(),
                        Boolean.TRUE.equals(c.getGiven()),
                        c.getDetail(),
                        c.getRecordedAt() == null ? null : c.getRecordedAt().toInstant()),
                body.getLinkCaseId()));
    return ResponseEntity.created(URI.create("/api/v1/cs-intakes/" + r.intakeId()))
        .body(toModel(r));
  }

  @Override
  public ResponseEntity<Intake> getCsIntake(String intakeId) {
    return ResponseEntity.ok(toModel(intakes.get(intakeId)));
  }

  @Override
  public ResponseEntity<ListCsIntakes200Response> listCsIntakes(
      @Nullable String caseId,
      @Nullable String customerRef,
      @Nullable String subjectRef,
      Integer size,
      @Nullable String cursor) {
    CsIntakeService.Page page =
        intakes.list(caseId, customerRef, subjectRef, size == null ? 50 : size, cursor);
    return ResponseEntity.ok(
        new ListCsIntakes200Response()
            .items(page.items().stream().map(IntakesController::toModel).toList())
            .page(
                new ListCases200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size())));
  }

  private static Intake toModel(IntakeRow r) {
    return new Intake()
        .intakeId(r.intakeId())
        .intakeType(Intake.IntakeTypeEnum.CS)
        .caseId(r.caseId())
        .inquiryId(r.inquiryId())
        .linked(r.linked())
        .channel(r.channel())
        .category(r.category())
        .customerRef(r.customerId())
        .subjectRef(r.subjectRef())
        .summary(r.summary())
        .callerRelation(Intake.CallerRelationEnum.fromValue(r.callerRelation()))
        .identityCheck(
            new IntakeIdentityCheck()
                .method(IntakeIdentityCheck.MethodEnum.fromValue(r.identityMethod()))
                .note(r.identityNote()))
        .consentRecorded(r.consentType() != null)
        .consent(
            r.consentType() == null
                ? null
                : new IntakeConsent()
                    .consentType(r.consentType())
                    .given(r.consentGiven())
                    .detail(r.consentDetail())
                    .recordedAt(odt(r.consentRecordedAt())))
        .receivedBy(r.receivedBy())
        .receivedAt(odt(r.receivedAt()));
  }

  private static ShiftHandover toModel(ShiftHandoverService.View v) {
    HandoverRow h = v.row();
    ShiftHandover m =
        new ShiftHandover()
            .shiftHandoverId(h.handoverId())
            .group(h.group())
            .centerId(h.centerId())
            .fromOperatorId(h.fromOperatorId())
            .toOperatorId(h.toOperatorId())
            .createdBy(h.createdBy())
            .state(ShiftHandover.StateEnum.fromValue(h.state()))
            .unreceived(v.unreceived())
            .note(h.note())
            .createdAt(odt(h.createdAt()))
            .completedAt(odt(h.completedAt()))
            .cancelledAt(odt(h.cancelledAt()));
    for (ItemRow i : v.items()) {
      m.addItemsItem(
          new ShiftHandoverItemsInner()
              .caseId(i.caseId())
              .nextAction(i.nextAction())
              .checkDueAt(odt(i.checkDueAt()))
              .caseStateAt(i.caseStateAt())
              .priorityAt(i.priorityAt())
              .openObligationsAt(i.openObligationsAt())
              .outcome(ShiftHandoverItemsInner.OutcomeEnum.fromValue(i.outcome()))
              .resolvedAt(odt(i.resolvedAt())));
    }
    for (Map<String, Object> s : h.teamSnapshot()) {
      m.addTeamSnapshotItem(
          new ShiftHandoverTeamSnapshotInner()
              .caseId(str(s.get("caseId")))
              .kind(str(s.get("kind")))
              .state(str(s.get("state")))
              .priority(str(s.get("priority")))
              .assigneeId(str(s.get("assigneeId")))
              .openObligationCount(
                  s.get("openObligationCount") instanceof Number n ? n.intValue() : 0));
    }
    return m;
  }

  private static @Nullable String str(@Nullable Object o) {
    return o == null ? null : o.toString();
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
