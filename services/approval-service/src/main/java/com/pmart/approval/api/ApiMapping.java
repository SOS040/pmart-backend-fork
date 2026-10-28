package com.pmart.approval.api;

import com.pmart.approval.api.generated.model.Approval;
import com.pmart.approval.api.generated.model.ApprovalSummary;
import com.pmart.approval.api.generated.model.ApprovalType;
import com.pmart.approval.api.generated.model.ChangeRequest;
import com.pmart.approval.api.generated.model.Decision;
import com.pmart.approval.api.generated.model.DeliveryState;
import com.pmart.approval.api.generated.model.ExecutionState;
import com.pmart.approval.api.generated.model.RequestState;
import com.pmart.approval.api.generated.model.ReviewState;
import com.pmart.approval.api.generated.model.SubmitChangeRequest202Response;
import com.pmart.approval.application.ApprovalService.Operator;
import com.pmart.approval.domain.ApprovalRules;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.ChangeRequestRow;
import com.pmart.approval.infrastructure.ApprovalStore.DecisionRow;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 저장 행 ↔ 계약 DTO 변환과 호출자 확인. 업무 규칙 없음. */
@Component
class ApiMapping {

  private final ObjectMapper mapper;

  ApiMapping(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 운영자 토큰만 허용(변경 요청·심사는 백오피스 행위). */
  static Operator operator(String sourceSystem) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ApprovalRules.forbidden("운영자만 할 수 있습니다."));
    return new Operator(p.subject(), p.roles(), sourceSystem);
  }

  ChangeRequest changeRequest(ChangeRequestRow r) {
    ChangeRequest dto = mapper.readValue(r.body(), ChangeRequest.class);
    return dto.changeRequestId(r.changeRequestId())
        .version(r.version())
        .requestState(RequestState.fromValue(r.requestState()))
        .requesterId(r.requesterId())
        .approvalId(r.approvalId())
        .createdAt(odt(r.createdAt()))
        .submittedAt(odt(r.submittedAt()));
  }

  Approval approval(ApprovalRow r, List<DecisionRow> decisions) {
    Approval dto = mapper.readValue(r.unit(), Approval.class);
    return dto.approvalId(r.approvalId())
        .changeRequestId(r.changeRequestId())
        .type(ApprovalType.fromValue(r.type()))
        .targetService(r.targetService())
        .targetRef(r.targetRef())
        .requesterId(r.requesterId())
        .requestState(RequestState.SUBMITTED)
        .reviewState(ReviewState.fromValue(r.reviewState()))
        .executionState(ExecutionState.fromValue(r.executionState()))
        .deliveryState(DeliveryState.fromValue(r.deliveryState()))
        .submittedAt(odt(r.submittedAt()))
        .expiresAt(odt(r.expiresAt()))
        .effectiveFrom(odt(r.effectiveFrom()))
        .version(r.version())
        .approvalVersion(1)
        .decisions(decisions.stream().map(this::decision).toList());
  }

  ApprovalSummary summary(ApprovalRow r) {
    Approval full = mapper.readValue(r.unit(), Approval.class);
    return new ApprovalSummary()
        .approvalId(r.approvalId())
        .changeRequestId(r.changeRequestId())
        .type(ApprovalType.fromValue(r.type()))
        .targetService(r.targetService())
        .targetRef(r.targetRef())
        .summary(full.getSummary())
        .requesterId(r.requesterId())
        .requestState(RequestState.SUBMITTED)
        .reviewState(ReviewState.fromValue(r.reviewState()))
        .executionState(ExecutionState.fromValue(r.executionState()))
        .deliveryState(DeliveryState.fromValue(r.deliveryState()))
        .submittedAt(odt(r.submittedAt()))
        .expiresAt(odt(r.expiresAt()));
  }

  Decision decision(DecisionRow r) {
    Decision dto = mapper.readValue(r.body(), Decision.class);
    return dto.decisionId(r.decisionId())
        .approvalId(r.approvalId())
        .approverId(r.approverId())
        .decidedAt(odt(r.decidedAt()));
  }

  static SubmitChangeRequest202Response command(CommandResponse c) {
    return new SubmitChangeRequest202Response()
        .status(SubmitChangeRequest202Response.StatusEnum.fromValue(c.status().name()))
        .requestId(c.requestId())
        .resultId(c.resultId())
        .appliedQty(c.appliedQty())
        .version(c.version())
        .retryable(c.retryable())
        .nextAction(c.nextAction())
        .occurredAt(odt(c.occurredAt()));
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
