package com.pmart.approval.api;

import com.pmart.approval.api.generated.model.ApplicationReceipt;
import com.pmart.approval.api.generated.model.ApplicationReceiptLine;
import com.pmart.approval.api.generated.model.Approval;
import com.pmart.approval.api.generated.model.ApprovalRefReason;
import com.pmart.approval.api.generated.model.ApprovalRefResult;
import com.pmart.approval.api.generated.model.ApprovalRefUse;
import com.pmart.approval.api.generated.model.ApprovalSummary;
import com.pmart.approval.api.generated.model.ApprovalType;
import com.pmart.approval.api.generated.model.ChangeRequest;
import com.pmart.approval.api.generated.model.DecideBlockedReason;
import com.pmart.approval.api.generated.model.Decision;
import com.pmart.approval.api.generated.model.DeliveryState;
import com.pmart.approval.api.generated.model.ExecutionState;
import com.pmart.approval.api.generated.model.RequestState;
import com.pmart.approval.api.generated.model.ReviewState;
import com.pmart.approval.api.generated.model.SubmitChangeRequest202Response;
import com.pmart.approval.application.ApprovalRefService;
import com.pmart.approval.application.ApprovalService.Operator;
import com.pmart.approval.domain.ApprovalRules;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.ChangeRequestRow;
import com.pmart.approval.infrastructure.ApprovalStore.DecisionRow;
import com.pmart.approval.infrastructure.ApprovalStore.ReceiptRow;
import com.pmart.approval.infrastructure.ApprovalStore.UseRow;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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

  /**
   * @param viewerBlock 보는 사람이 결정할 수 없는 이유(AP-3), 할 수 있으면 null
   */
  Approval approval(
      ApprovalRow r,
      List<DecisionRow> decisions,
      List<ReceiptRow> receipts,
      ApprovalRules.@Nullable DecideBlock viewerBlock) {
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
        .viewerCanDecide(viewerBlock == null)
        .viewerDecideBlockedReason(blockedReason(viewerBlock))
        .version(r.version())
        .approvalVersion(1)
        .decisions(decisions.stream().map(this::decision).toList())
        .receipts(receipts.stream().map(this::receipt).toList());
  }

  /** 반영 회신(ADR-0039). 행별 결과는 저장한 JSON 사본에서 읽는다. */
  ApplicationReceipt receipt(ReceiptRow r) {
    return new ApplicationReceipt()
        .receiptId(r.receiptId())
        .approvalId(r.approvalId())
        .receiverService(r.receiverService())
        .receiptSeq(r.receiptSeq())
        .result(ApplicationReceipt.ResultEnum.fromValue(r.result()))
        .domainRef(r.domainRef())
        .reason(r.reason())
        .lines(Arrays.asList(mapper.readValue(r.lines(), ApplicationReceiptLine[].class)))
        .occurredAt(odt(r.occurredAt()))
        .receivedAt(odt(r.receivedAt()))
        // 회신이 왔다 = 정본이 승인 사건을 받아 처리했다
        .deliveryState(DeliveryState.ACKNOWLEDGED);
  }

  /** 승인 번호 판정(AP-1). 승인이 있으면 승인 단위(행 포함)를 함께 — 정본이 자기 집행이 승인 범위 안인지 본다. */
  ApprovalRefResult approvalRef(ApprovalRefService.Result r) {
    ApprovalRefResult dto =
        new ApprovalRefResult()
            .approvalId(r.approvalId())
            .usable(r.usable())
            .reason(r.reason() == null ? null : ApprovalRefReason.fromValue(r.reason().name()));
    ApprovalRow ap = r.approval();
    if (ap != null) {
      dto.type(ApprovalType.fromValue(ap.type()))
          .targetService(ap.targetService())
          .targetRef(ap.targetRef())
          .reviewState(ReviewState.fromValue(ap.reviewState()))
          .requesterId(ap.requesterId())
          .effectiveFrom(odt(ap.effectiveFrom()))
          .expiresAt(odt(ap.expiresAt()))
          .lines(mapper.readValue(ap.unit(), Approval.class).getLines());
    }
    DecisionRow d = r.approved();
    if (d != null) {
      dto.approverId(d.approverId()).decidedAt(odt(d.decidedAt()));
    }
    UseRow u = r.use();
    if (u != null) {
      dto.use(
          new ApprovalRefUse()
              .useRef(u.useRef())
              .usedByService(u.usedByService())
              .actorId(u.actorId())
              .usedAt(odt(u.usedAt())));
    }
    return dto;
  }

  ApprovalSummary summary(ApprovalRow r, ApprovalRules.@Nullable DecideBlock viewerBlock) {
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
        .expiresAt(odt(r.expiresAt()))
        .viewerCanDecide(viewerBlock == null)
        .viewerDecideBlockedReason(blockedReason(viewerBlock));
  }

  private static @Nullable DecideBlockedReason blockedReason(
      ApprovalRules.@Nullable DecideBlock block) {
    return block == null ? null : DecideBlockedReason.fromValue(block.name());
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
