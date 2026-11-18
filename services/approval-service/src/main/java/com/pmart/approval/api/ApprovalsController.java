package com.pmart.approval.api;

import com.pmart.approval.api.generated.ApprovalsApi;
import com.pmart.approval.api.generated.model.Approval;
import com.pmart.approval.api.generated.model.ApprovalType;
import com.pmart.approval.api.generated.model.Decision;
import com.pmart.approval.api.generated.model.DecisionRequest;
import com.pmart.approval.api.generated.model.ExecutionState;
import com.pmart.approval.api.generated.model.ListApplicationReceipts200Response;
import com.pmart.approval.api.generated.model.ListApprovals200Response;
import com.pmart.approval.api.generated.model.ListApprovals200ResponsePage;
import com.pmart.approval.api.generated.model.ReviewState;
import com.pmart.approval.api.generated.model.SubmitChangeRequest202Response;
import com.pmart.approval.application.ApprovalService;
import com.pmart.approval.application.ApprovalService.NewDecision;
import com.pmart.approval.application.ApprovalService.Operator;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ApprovalsApi 의 구현 진입점. 최소 범위(승인함·상세·결정·결정 조회). 재심사는 인터페이스 default 501. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class ApprovalsController implements ApprovalsApi {

  private final ApprovalService service;
  private final ApiMapping mapping;

  public ApprovalsController(ApprovalService service, ApiMapping mapping) {
    this.service = service;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<ListApprovals200Response> listApprovals(
      @Nullable ApprovalType type,
      @Nullable ReviewState reviewState,
      @Nullable ExecutionState executionState,
      @Nullable String centerId,
      @Nullable String assigneeId,
      @Nullable String requesterId,
      @Nullable String targetRef,
      @Nullable Boolean decidableByMe,
      Integer size,
      @Nullable String cursor) {
    Operator viewer = ApiMapping.operator("backoffice-web");
    Cursor after = Cursor.decode(cursor);
    List<ApprovalRow> rows =
        service.list(
            type == null ? null : type.getValue(),
            reviewState == null ? null : reviewState.getValue(),
            executionState == null ? null : executionState.getValue(),
            requesterId,
            targetRef,
            Boolean.TRUE.equals(decidableByMe) ? viewer : null,
            after == null ? null : after.submittedAt(),
            after == null ? null : after.approvalId(),
            size + 1);
    boolean more = rows.size() > size;
    List<ApprovalRow> page = more ? rows.subList(0, size) : rows;
    ApprovalRow last = page.isEmpty() ? null : page.get(page.size() - 1);
    return ResponseEntity.ok(
        new ListApprovals200Response()
            .items(
                page.stream().map(r -> mapping.summary(r, service.viewerBlock(r, viewer))).toList())
            .page(
                new ListApprovals200ResponsePage()
                    .size(page.size())
                    .nextCursor(more && last != null ? Cursor.encode(last) : null)));
  }

  @Override
  public ResponseEntity<Approval> getApproval(String approvalId) {
    Operator viewer = ApiMapping.operator("backoffice-web");
    ApprovalRow ap = service.getApproval(approvalId);
    return ResponseEntity.ok(
        mapping.approval(
            ap,
            service.decisions(approvalId),
            service.receipts(approvalId),
            service.viewerBlock(ap, viewer)));
  }

  @Override
  public ResponseEntity<ListApplicationReceipts200Response> listApplicationReceipts(
      String approvalId) {
    ApiMapping.operator("backoffice-web");
    service.getApproval(approvalId); // 없으면 404
    return ResponseEntity.ok(
        new ListApplicationReceipts200Response()
            .approvalId(approvalId)
            .items(service.receipts(approvalId).stream().map(mapping::receipt).toList()));
  }

  @Override
  public ResponseEntity<SubmitChangeRequest202Response> decideApproval(
      String approvalId,
      String idempotencyKey,
      String xSourceSystem,
      DecisionRequest body,
      @Nullable Integer xExpectedVersion) {
    var outcome =
        service.decide(
            idempotencyKey,
            ApiMapping.operator(xSourceSystem),
            approvalId,
            xExpectedVersion,
            new NewDecision(
                body.getDecision().getValue(),
                body.getEffectiveFrom() == null ? null : body.getEffectiveFrom().toInstant(),
                body.getExpiresAt() == null ? null : body.getExpiresAt().toInstant(),
                body.getConditions() == null ? List.of() : body.getConditions(),
                body));
    return ResponseEntity.accepted().body(ApiMapping.command(outcome.value()));
  }

  @Override
  public ResponseEntity<Decision> getDecision(String approvalId, String decisionId) {
    ApiMapping.operator("backoffice-web");
    return ResponseEntity.ok(mapping.decision(service.getDecision(approvalId, decisionId)));
  }

  /** 승인함 커서: (submittedAt, approvalId) — 불투명 문자열로 노출. */
  record Cursor(Instant submittedAt, String approvalId) {
    static @Nullable Cursor decode(@Nullable String cursor) {
      if (cursor == null || cursor.isBlank()) {
        return null;
      }
      try {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        return new Cursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }

    static String encode(ApprovalRow last) {
      String raw = last.submittedAt() + "|" + last.approvalId();
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
  }
}
