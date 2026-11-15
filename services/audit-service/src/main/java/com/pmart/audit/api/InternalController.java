package com.pmart.audit.api;

import com.pmart.audit.api.generated.InternalApi;
import com.pmart.audit.api.generated.model.Actor;
import com.pmart.audit.api.generated.model.PiiAccessLog;
import com.pmart.audit.api.generated.model.PiiAccessLogInput;
import com.pmart.audit.api.generated.model.PiiAccessLogRecorded;
import com.pmart.audit.application.PiiAccessLogService;
import com.pmart.audit.domain.PiiAccess;
import com.pmart.audit.infrastructure.PiiAccessLogStore;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 개인정보 열람 기록 기록·조회(BO-25, 0.2.0). 감사 사건 기록(recordAuditEntry)은 아직 인터페이스 default
 * 501. 요청·응답 변환만 한다. 열람 사유(purpose)는 로그에 남기지 않는다.
 */
@RestController
public class InternalController implements InternalApi {

  private final PiiAccessLogService service;

  public InternalController(PiiAccessLogService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<PiiAccessLogRecorded> recordPiiAccessLog(
      String idempotencyKey,
      String xSourceSystem,
      PiiAccessLogInput body,
      @Nullable String traceparent) {
    Actor a = body.getActor();
    var access =
        new PiiAccess(
            body.getOccurredAt().toInstant(),
            a.getActorId(),
            a.getActorType().getValue(),
            a.getOnBehalfOf(),
            a.getRoleGrantRef(),
            orEmpty(a.getRoles()),
            orEmpty(a.getCenterScope()),
            body.getSubjectType() == null
                ? PiiAccessLogInput.SubjectTypeEnum.CUSTOMER.getValue()
                : body.getSubjectType().getValue(),
            body.getSubjectRef(),
            body.getAccessType().getValue(),
            body.getFields(),
            body.getPurpose(),
            body.getScreen(),
            body.getCaseRef(),
            body.getRecordCount() == null ? 1 : body.getRecordCount(),
            body.getTraceId());
    PiiAccessLogService.Recorded r = service.record(xSourceSystem, idempotencyKey, access);
    return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(
            new PiiAccessLogRecorded()
                .piiAccessLogId(r.piiAccessLogId())
                .recordedAt(r.recordedAt().atOffset(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<PiiAccessLog> getPiiAccessLog(String piiAccessLogId) {
    return ResponseEntity.ok(dto(service.get(piiAccessLogId)));
  }

  /** 저장된 열람 기록 → 계약 PiiAccessLog(내부 조회·직원 조회 공용). */
  static PiiAccessLog dto(PiiAccessLogStore.Stored s) {
    PiiAccess a = s.access();
    return new PiiAccessLog()
        .piiAccessLogId(s.piiAccessLogId())
        .recordedAt(s.recordedAt().atOffset(ZoneOffset.UTC))
        .sourceSystem(s.sourceSystem())
        .requestId(s.requestId())
        .occurredAt(a.occurredAt().atOffset(ZoneOffset.UTC))
        .actor(
            new Actor()
                .actorId(a.actorId())
                .actorType(Actor.ActorTypeEnum.fromValue(a.actorType()))
                .onBehalfOf(a.actorOnBehalfOf())
                .roleGrantRef(a.actorRoleGrantRef())
                .roles(a.actorRoles())
                .centerScope(a.actorCenterScope()))
        .subjectType(PiiAccessLog.SubjectTypeEnum.fromValue(a.subjectType()))
        .subjectRef(a.subjectRef())
        .accessType(PiiAccessLog.AccessTypeEnum.fromValue(a.accessType()))
        .fields(a.fields())
        .purpose(a.purpose())
        .screen(a.screen())
        .caseRef(a.caseRef())
        .recordCount(a.recordCount())
        .traceId(a.traceId());
  }

  private static List<String> orEmpty(@Nullable List<String> l) {
    return l == null ? List.of() : l;
  }
}
