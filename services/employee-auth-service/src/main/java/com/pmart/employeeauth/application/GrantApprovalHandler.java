package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 직원 권한 승인 반영(ADR-0056 5-4, BO-01 "승인한 권한행만 유효 grant 로"). approval-service {@code
 * ApprovalDecided}(type GRANT, 정본 = 이 서비스) 중 승인된 행을 {@code employee_grant} 로 저장한다. inbox 트랜잭션 안(같은
 * 사건 두 번 반영 없음 — 승인·행 UNIQUE 로 한 번 더 막는다).
 *
 * <ul>
 *   <li>행 속성: role·centers·validFrom(그날 00:00 Asia/Seoul 부터)·validTo(그날까지 — 다음 날 00:00 부터
 *       무효)·reason.
 *   <li>모르는 역할(설정 {@code pmart.employee-auth.roles} 밖)은 그 행만 거절(UNKNOWN_ROLE).
 *   <li>이미 유효하거나 시작 전인 같은 권한(역할 같음 + 센터 겹침 + 기간이 지금 이후로 겹침 — {@link GrantOverlap})과 겹치는 행은 새 줄을 만들지
 *       않고 회신에 REJECTED(GRANT_ALREADY_ACTIVE, domainRef = 기존 grantId)로 남긴다 — GrantApplied
 *       없음(2026-11-17 HR-2 안전망: 같은 권한이 두 줄이면 회수 때 한 줄만 끝나 권한이 남는다). 요청 단계에서는 hr-service 가 409 로 먼저
 *       막는다. 같은 직원의 반영은 advisory lock 으로 한 줄로 세운다(서로 다른 승인이 동시에 와도 서로를 본다).
 *   <li>행마다 {@code GrantApplied}(platform.auth.v1 — audit 등), 승인 전체로 {@code
 *       ApplicationReceiptRecorded} (platform.approval-receipt.v1 — approval 실행 축, ADR-0039).
 *   <li>반려(REJECTED)는 반영할 것이 없다. 이미 나간 토큰에는 다음 발급·갱신(15분 안)부터 실린다.
 * </ul>
 */
@Component
public class GrantApprovalHandler {

  private static final Logger log = LoggerFactory.getLogger(GrantApprovalHandler.class);
  public static final String SERVICE = "employee-auth-service";
  static final String GRANT_TOPIC = "platform.auth.v1";
  static final String RECEIPT_TOPIC = "platform.approval-receipt.v1";

  /** 회신 행 거절 사유 — 이미 유효·예정인 같은 권한(HR-2). hr-service 409 코드와 같은 이름. */
  static final String ALREADY_ACTIVE = "GRANT_ALREADY_ACTIVE";

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final EmployeeAuthStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final EmployeeAuthProperties props;
  private final Clock clock;

  public GrantApprovalHandler(
      EmployeeAuthStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      EmployeeAuthProperties props,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.clock = clock;
  }

  /** 구독 필터: 권한 승인이고 정본이 이 서비스. */
  public static boolean mine(EventEnvelope e) {
    JsonNode p = e.payload();
    return "ApprovalDecided".equals(e.eventType())
        && "GRANT".equals(p.path("type").asString(null))
        && SERVICE.equals(p.path("targetRef").path("targetService").asString(null));
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String approvalId = p.path("approvalId").asString();
    String decision = p.path("decision").asString();
    if (!"APPROVED".equals(decision) && !"PARTIALLY_APPROVED".equals(decision)) {
      log.info("권한 승인 반려 — 반영 없음: approvalId={}", approvalId);
      return;
    }
    String operatorId = p.path("targetRef").path("targetId").asString();
    String approverId = p.path("approverId").asString();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    store.lockGrantsOf(operatorId);

    List<Map<String, Object>> receiptLines = new ArrayList<>();
    int applied = 0;
    int rejected = 0;
    for (JsonNode line : p.path("lines")) {
      String lineId = line.path("lineId").asString();
      if (!"APPROVED".equals(line.path("decision").asString("APPROVED"))) {
        continue; // 반려된 행(부분 승인)
      }
      JsonNode a = line.path("attributes");
      String role = a.path("role").asString(null);
      if (role == null || !props.roles().contains(role)) {
        rejected++;
        receiptLines.add(line(lineId, "REJECTED", "UNKNOWN_ROLE", null));
        log.warn("모르는 역할의 권한 행 거절: approvalId={} lineId={}", approvalId, lineId);
        continue;
      }
      List<String> centers = new ArrayList<>();
      a.path("centers").forEach(c -> centers.add(c.asString()));
      Instant from = start(a.path("validFrom").asString(null), p);
      Instant until = untilExclusive(a.path("validTo").asString(null));
      String grantId = approvalId + ":" + lineId;
      EmployeeAuthStore.GrantRow existing =
          alreadyActive(operatorId, grantId, role, centers, from, until, now);
      if (existing != null) {
        rejected++;
        receiptLines.add(line(lineId, "REJECTED", ALREADY_ACTIVE, existing.grantId()));
        // 운영자 ID·역할·grant ID 만(이름·사유 글은 남기지 않는다)
        log.warn(
            "이미 유효·예정인 같은 권한과 겹쳐 새 grant 를 만들지 않음: approvalId={} lineId={} operatorId={} role={}"
                + " existingGrantId={}",
            approvalId,
            lineId,
            operatorId,
            role,
            existing.grantId());
        continue;
      }
      boolean inserted =
          store.insertGrant(
              grantId,
              operatorId,
              role,
              centers,
              from,
              until,
              approvalId,
              lineId,
              approverId,
              a.path("reason").asString(null),
              now);
      applied++;
      receiptLines.add(line(lineId, "APPLIED", null, grantId));
      if (inserted) {
        publishGrantApplied(grantId, approvalId, p, operatorId, role, centers, from, until);
      }
    }
    String result = applied == 0 ? "REJECTED" : rejected == 0 ? "APPLIED" : "PARTIAL";
    publishReceipt(approvalId, result, receiptLines);
    log.info(
        "권한 승인 반영: approvalId={} operatorId={} → {}(반영 {}, 거절 {})",
        approvalId,
        operatorId,
        result,
        applied,
        rejected);
  }

  /**
   * 이 행과 겹치는 이미 유효·예정인 같은 권한(없으면 null). 같은 grant ID(재전달)는 빼서 재전달은 예전처럼 APPLIED 로 끝난다. 같은 승인 안에서 먼저
   * 반영한 행도 같은 트랜잭션이라 보인다.
   */
  private EmployeeAuthStore.@Nullable GrantRow alreadyActive(
      String operatorId,
      String grantId,
      String role,
      List<String> centers,
      Instant from,
      @Nullable Instant until,
      Instant now) {
    boolean centerScoped = props.centerScoped(role);
    GrantOverlap.Period requested = new GrantOverlap.Period(from, until);
    for (EmployeeAuthStore.GrantRow g : store.grantsActiveOrFuture(operatorId, now)) {
      if (!g.grantId().equals(grantId)
          && g.role().equals(role)
          && GrantOverlap.centersOverlap(centerScoped, g.centers(), centers)
          && GrantOverlap.periodsOverlap(
              requested, new GrantOverlap.Period(g.validFrom(), g.validUntil()), now)) {
        return g;
      }
    }
    return null;
  }

  private void publishGrantApplied(
      String grantId,
      String approvalId,
      JsonNode p,
      String operatorId,
      String role,
      List<String> centers,
      Instant from,
      @Nullable Instant until) {
    Map<String, Object> approvalRef = new LinkedHashMap<>();
    approvalRef.put("approvalId", approvalId);
    if (p.hasNonNull("dossierVersion")) {
      approvalRef.put("dossierVersion", p.get("dossierVersion").asInt());
    }
    approvalRef.put("approverId", p.path("approverId").asString());
    Map<String, Object> effective = new LinkedHashMap<>();
    effective.put("from", from.toString());
    effective.put("to", until == null ? null : until.toString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("grantId", grantId);
    payload.put("approvalRef", approvalRef);
    payload.put("operatorId", operatorId);
    payload.put("roles", List.of(role));
    payload.put("centerScope", centers);
    payload.put("effective", effective);
    events.publish(
        codec.create(
            events.sourceSystem(),
            "GrantApplied",
            1,
            "Grant",
            grantId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, SERVICE),
            EventEnvelope.EventSource.REAL,
            payload),
        GRANT_TOPIC);
  }

  /** 반영 회신 — 한 번에 끝나는 반영이라 순번 1(approval 이 승인·수신자·순번으로 한 번만 받는다). */
  private void publishReceipt(String approvalId, String result, List<Map<String, Object>> lines) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("receiverService", SERVICE);
    payload.put("receiptSeq", 1);
    payload.put("result", result);
    payload.put("lines", lines);
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ApplicationReceiptRecorded",
            1,
            "ApplicationReceipt",
            approvalId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, SERVICE),
            EventEnvelope.EventSource.REAL,
            payload),
        RECEIPT_TOPIC);
  }

  private static Map<String, Object> line(
      String lineKey, String result, @Nullable String reasonCode, @Nullable String domainRef) {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("lineKey", lineKey);
    l.put("result", result);
    l.put("reasonCode", reasonCode);
    l.put("domainRef", domainRef);
    return l;
  }

  /** 행 시작일 00:00 Asia/Seoul. 없으면 승인의 effectiveFrom, 그것도 없으면 결정 시각. */
  private static Instant start(@Nullable String validFrom, JsonNode p) {
    if (validFrom != null && !validFrom.isBlank()) {
      return LocalDate.parse(validFrom).atStartOfDay(SEOUL).toInstant();
    }
    String eff = p.path("effectiveFrom").asString(null);
    return Instant.parse(eff != null ? eff : p.path("decidedAt").asString());
  }

  private static @Nullable Instant untilExclusive(@Nullable String validTo) {
    return validTo == null || validTo.isBlank()
        ? null
        : LocalDate.parse(validTo).plusDays(1).atStartOfDay(SEOUL).toInstant();
  }
}
