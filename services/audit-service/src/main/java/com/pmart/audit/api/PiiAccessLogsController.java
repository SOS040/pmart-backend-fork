package com.pmart.audit.api;

import com.pmart.audit.api.generated.PiiAccessLogsApi;
import com.pmart.audit.api.generated.model.ListAuditEntries200ResponsePage;
import com.pmart.audit.api.generated.model.ListPiiAccessLogs200Response;
import com.pmart.audit.api.generated.model.PiiAccessLog;
import com.pmart.audit.application.PiiAccessLogService;
import com.pmart.audit.infrastructure.PiiAccessLogStore;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PiiAccessLogsApi(0.3.0, 2026-11-16) — 직원용 개인정보 열람 기록 조회. OPERATOR_ADMIN 만(ADR-0062 결정 10), 권한
 * 판정은 {@link PiiAccessLogService}. 열람 사유(purpose)는 응답에만 — 로그에 남기지 않는다.
 */
@RestController
public class PiiAccessLogsController implements PiiAccessLogsApi {

  private final PiiAccessLogService service;

  public PiiAccessLogsController(PiiAccessLogService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListPiiAccessLogs200Response> listPiiAccessLogs(
      @Nullable String actorId,
      @Nullable String subjectType,
      @Nullable String subjectRef,
      @Nullable String caseRef,
      @Nullable String accessType,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      Integer size,
      @Nullable String cursor) {
    PiiAccessLogService.StaffPage page =
        service.list(
            new PiiAccessLogStore.Search(
                blankToNull(actorId),
                subjectType,
                blankToNull(subjectRef),
                blankToNull(caseRef),
                accessType,
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant()),
            cursor,
            size);
    return ResponseEntity.ok(
        new ListPiiAccessLogs200Response()
            .items(page.items().stream().map(InternalController::dto).toList())
            .page(
                new ListAuditEntries200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size())));
  }

  @Override
  public ResponseEntity<PiiAccessLog> getPiiAccessLogForStaff(String piiAccessLogId) {
    return ResponseEntity.ok(InternalController.dto(service.getForStaff(piiAccessLogId)));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
