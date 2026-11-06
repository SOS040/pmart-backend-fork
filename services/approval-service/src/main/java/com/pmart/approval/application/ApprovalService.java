package com.pmart.approval.application;

import com.pmart.approval.domain.ApprovalRules;
import com.pmart.approval.infrastructure.ApprovalStore;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.ChangeRequestRow;
import com.pmart.approval.infrastructure.ApprovalStore.DecisionRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 변경 요청·승인 최소 흐름(ADR-0014). 모든 변경 명령은 {@link RequestIdempotency} 트랜잭션 안에서 상태 저장과 Outbox 기록을 함께 한다.
 */
@Service
public class ApprovalService {

  /** 명령 호출자(운영자). */
  public record Operator(String id, List<String> roles, String sourceSystem) {}

  /** 변경 요청 생성 입력. {@code body} 는 계약의 ChangeRequestBody 그대로(JSON 스냅샷으로 보존). */
  public record NewChangeRequest(
      String type, String targetService, String targetRef, Object body) {}

  /** 결정 입력. {@code body} 는 계약의 DecisionRequest 그대로. */
  public record NewDecision(
      String decision,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      List<String> conditions,
      Object body) {}

  private final ApprovalStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final ApprovalProperties properties;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ApprovalService(
      ApprovalStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      ApprovalProperties properties,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.properties = properties;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome<ChangeRequestRow> createChangeRequest(
      String requestId, Operator operator, NewChangeRequest in) {
    return idempotency.execute(
        key(requestId, "createChangeRequest", operator),
        in.body(),
        ChangeRequestRow.class,
        () -> {
          ChangeRequestRow row =
              new ChangeRequestRow(
                  "CR-" + UUID.randomUUID(),
                  1,
                  in.type(),
                  in.targetService(),
                  in.targetRef(),
                  "DRAFT",
                  operator.id(),
                  null,
                  mapper.writeValueAsString(in.body()),
                  now(),
                  null);
          store.insert(row);
          return row;
        });
  }

  public ChangeRequestRow getChangeRequest(String id) {
    return store
        .findChangeRequest(id)
        .orElseThrow(() -> PmartException.notFound("CHANGE_REQUEST_NOT_FOUND", "변경 요청이 없습니다."));
  }

  /** DRAFT → SUBMITTED, 승인 단위 확정(approvalId 발급), ChangeRequestSubmitted. */
  public Outcome<CommandResponse> submit(
      String requestId,
      Operator operator,
      String changeRequestId,
      @Nullable Integer expectedVersion) {
    return idempotency.execute(
        key(requestId, "submitChangeRequest", operator),
        Map.of("changeRequestId", changeRequestId),
        CommandResponse.class,
        () -> {
          ChangeRequestRow cr = getChangeRequest(changeRequestId);
          checkVersion(expectedVersion, cr.version());
          if (!cr.requesterId().equals(operator.id())) {
            throw ApprovalRules.forbidden("발의자만 제출할 수 있습니다.");
          }
          ApprovalRules.requireDraft(cr.requestState());
          JsonNode body = mapper.readTree(cr.body());
          Instant at = now();
          String approvalId = "AP-" + UUID.randomUUID();
          store.insert(
              new ApprovalRow(
                  approvalId,
                  1,
                  cr.changeRequestId(),
                  cr.type(),
                  cr.targetService(),
                  cr.targetRef(),
                  cr.requesterId(),
                  "REVIEW_PENDING",
                  "NOT_REQUESTED",
                  "PENDING",
                  cr.body(),
                  instantOrNull(body, "requestedEffectiveFrom"),
                  instantOrNull(body, "expiresAt"),
                  at));
          store.markSubmitted(cr.changeRequestId(), cr.version(), approvalId, at);

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("changeRequestId", cr.changeRequestId());
          payload.put("type", cr.type());
          payload.put(
              "targetRef",
              Map.of(
                  "targetType", ApprovalRules.targetType(cr.type()),
                  "targetId", cr.targetRef(),
                  "targetService", cr.targetService()));
          if (body.hasNonNull("targetExpectedVersion")) {
            payload.put("expectedVersion", body.get("targetExpectedVersion").asInt());
          }
          payload.put("payloadRef", "approval-service/change-requests/" + cr.changeRequestId());
          payload.put("submittedBy", operator.id());
          payload.put("submittedAt", at.toString());
          events.publish(
              "ChangeRequestSubmitted",
              1,
              "ChangeRequest",
              cr.changeRequestId(),
              cr.version() + 1L,
              actor(operator),
              payload);
          return new CommandResponse(
              CommandStatus.APPLIED,
              requestId,
              approvalId,
              null,
              cr.version() + 1,
              false,
              null,
              at);
        });
  }

  public ApprovalRow getApproval(String approvalId) {
    return store
        .findApproval(approvalId)
        .orElseThrow(() -> PmartException.notFound("APPROVAL_NOT_FOUND", "승인이 없습니다."));
  }

  public List<DecisionRow> decisions(String approvalId) {
    return store.decisions(approvalId);
  }

  /** 반영 회신(ADR-0039), 받은 순. */
  public List<ApprovalStore.ReceiptRow> receipts(String approvalId) {
    return store.receipts(approvalId);
  }

  public DecisionRow getDecision(String approvalId, String decisionId) {
    return store
        .findDecision(approvalId, decisionId)
        .orElseThrow(() -> PmartException.notFound("DECISION_NOT_FOUND", "결정이 없습니다."));
  }

  public List<ApprovalRow> list(
      @Nullable String type,
      @Nullable String reviewState,
      @Nullable String executionState,
      @Nullable String requesterId,
      @Nullable String targetRef,
      @Nullable Instant afterSubmittedAt,
      @Nullable String afterId,
      int limit) {
    return store.list(
        type,
        reviewState,
        executionState,
        requesterId,
        targetRef,
        afterSubmittedAt,
        afterId,
        limit);
  }

  /** 심사 결정(APPROVE / REJECT) → ApprovalDecided. */
  public Outcome<CommandResponse> decide(
      String requestId,
      Operator operator,
      String approvalId,
      @Nullable Integer expectedVersion,
      NewDecision in) {
    return idempotency.execute(
        key(requestId, "decideApproval", operator),
        Map.of("approvalId", approvalId, "decision", in.body()),
        CommandResponse.class,
        () -> {
          ApprovalRow ap = getApproval(approvalId);
          checkVersion(expectedVersion, ap.version());
          ApprovalRules.requireSupported(in.decision());
          ApprovalRules.requireUnderReview(ap.reviewState());
          ApprovalRules.requireApprover(
              ap.type(),
              ap.requesterId(),
              operator.id(),
              operator.roles(),
              properties.getApproverRoles());

          Instant at = now();
          String decided = ApprovalRules.decidedState(in.decision());
          JsonNode unit = mapper.readTree(ap.unit());
          Instant effectiveFrom =
              in.effectiveFrom() != null
                  ? in.effectiveFrom()
                  : instantOrNull(unit, "requestedEffectiveFrom");
          Instant expiresAt = in.expiresAt() != null ? in.expiresAt() : ap.expiresAt();
          String decisionId = "DC-" + UUID.randomUUID();
          store.insert(
              new DecisionRow(
                  decisionId,
                  approvalId,
                  in.decision(),
                  operator.id(),
                  mapper.writeValueAsString(in.body()),
                  at));
          store.markDecided(approvalId, ap.version(), decided, effectiveFrom, expiresAt);

          events.publish(
              "ApprovalDecided",
              1,
              "Approval",
              approvalId,
              ap.version() + 1L,
              actor(operator),
              decidedPayload(ap, unit, decided, operator, in, effectiveFrom, expiresAt, at));
          return new CommandResponse(
              CommandStatus.APPLIED,
              requestId,
              decisionId,
              null,
              ap.version() + 1,
              false,
              null,
              at);
        });
  }

  private Map<String, Object> decidedPayload(
      ApprovalRow ap,
      JsonNode unit,
      String decided,
      Operator operator,
      NewDecision in,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      Instant at) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetType", ApprovalRules.targetType(ap.type()));
    target.put("targetId", ap.targetRef());
    target.put("targetService", ap.targetService());
    if (unit.hasNonNull("targetExpectedVersion")) {
      target.put("expectedVersion", unit.get("targetExpectedVersion").asInt());
    }

    // 승인 단위의 행(최소 범위: 요청 행 그대로 승인/반려). 정본은 attributes 로 변경 내용을 읽는다.
    List<Map<String, Object>> lines = new ArrayList<>();
    if (unit.has("lines")) {
      for (JsonNode l : unit.get("lines")) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", l.path("lineKey").asString());
        if (l.hasNonNull("requestedQty")) {
          line.put("qty", l.get("requestedQty").asInt());
        }
        if (l.hasNonNull("requestedAmount")) {
          line.put("amount", l.get("requestedAmount").asLong());
        }
        line.put("decision", decided);
        if (l.hasNonNull("targetRef")) {
          line.put("targetRef", l.get("targetRef").asString());
        }
        if (l.has("attributes")) {
          line.put("attributes", mapper.convertValue(l.get("attributes"), Map.class));
        }
        lines.add(line);
      }
    }

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("approvalId", ap.approvalId());
    p.put("changeRequestId", ap.changeRequestId());
    p.put("type", ap.type());
    p.put("decision", decided);
    p.put("targetRef", target);
    p.put("lines", lines);
    p.put("conditions", in.conditions().stream().map(c -> Map.of("code", c)).toList());
    if (effectiveFrom != null) {
      p.put("effectiveFrom", effectiveFrom.toString());
    }
    if (expiresAt != null) {
      p.put("expiresAt", expiresAt.toString());
    }
    p.put("approverId", operator.id());
    p.put("dossierVersion", ap.version() + 1);
    p.put("decidedAt", at.toString());
    return p;
  }

  private static RequestKey key(String requestId, String operation, Operator operator) {
    return new RequestKey(operator.sourceSystem(), requestId, operation, operator.id());
  }

  private static void checkVersion(@Nullable Integer expected, int current) {
    if (expected != null && expected != current) {
      throw PmartException.versionConflict(
          "대상이 이미 바뀌었습니다(기대 버전 " + expected + ", 현재 " + current + "). 다시 조회한 뒤 요청하세요.");
    }
  }

  private static EventEnvelope.Actor actor(Operator operator) {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator.id());
  }

  private static @Nullable Instant instantOrNull(JsonNode n, String field) {
    // 계약 date-time 은 오프셋을 포함할 수 있다(+09:00 등)
    return n.hasNonNull(field) ? OffsetDateTime.parse(n.get(field).asString()).toInstant() : null;
  }

  private Instant now() {
    return clock.instant();
  }
}
