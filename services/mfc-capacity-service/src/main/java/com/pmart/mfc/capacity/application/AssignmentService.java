package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 배정(WK-02·WK-05, 슬라이스 6b). Q-08(우선순위·자동 배정) 결정 전에는 권한자 수동 지정만 — 그 센터의 {@code MFC_SUPERVISOR}.
 * 작업자는 운영자 ID(작업자 로그인 BR-02 결정 전 잠정). 작업자 자격·근무·장비 정본이 없어 강제 제약은 아직 검사하지 않는다.
 */
@Service
public class AssignmentService {

  public static final String TOPIC = "mfc.capacity.v1";
  public static final String SUPERVISOR_ROLE = "MFC_SUPERVISOR";

  /** 업무 유형 목록과 하나로(RETURN 포함 — 2026-11-08 LAN E2E 에서 빠진 것 발견). */
  private static final Set<String> TASK_TYPES =
      com.pmart.mfc.capacity.domain.WorkerRules.TASK_TYPES;

  private static final Set<String> STATES = Set.of("ACTIVE", "SUPERSEDED", "RELEASED");
  private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

  public record AssignCommand(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      @Nullable String reason,
      @Nullable Integer expectedVersion,
      @Nullable String temperatureZone) {}

  /** created = false 면 이미 그 작업자(변화 없음). warnings = 막지 않는 경고(OFF_SHIFT). */
  public record Assigned(AssignmentRow assignment, boolean created, List<String> warnings) {}

  private final AssignmentStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;
  private final WorkerService workers;

  public AssignmentService(
      AssignmentStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock,
      WorkerService workers) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
    this.workers = workers;
  }

  public Assigned assign(String requestId, String sourceSystem, AssignCommand cmd) {
    String supervisor = requireSupervisor(cmd.mfcId());
    if (!TASK_TYPES.contains(cmd.taskType())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 작업 유형: " + cmd.taskType());
    }
    if (cmd.workerId().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workerId 가 비었습니다.");
    }
    // WK-02 강제 제약: 등록·ACTIVE·업무(작업 유형)·온도대 자격. 근무 밖은 경고만(Q-09 미결)
    String reject =
        workers.rejection(cmd.mfcId(), cmd.workerId(), cmd.taskType(), cmd.temperatureZone());
    if (reject != null) {
      throw new PmartException(
          reject,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "작업자 " + cmd.workerId() + " 에게 이 작업을 배정할 수 없습니다: " + reject,
          false,
          List.of());
    }
    List<String> warnings =
        workers.offShift(cmd.mfcId(), cmd.workerId(), clock.instant())
            ? List.of(com.pmart.mfc.capacity.domain.WorkerRules.OFF_SHIFT)
            : List.of();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "assignTask", supervisor),
            cmd,
            Assigned.class,
            () -> {
              Optional<AssignmentRow> active = store.lockActive(cmd.taskId());
              int current = active.map(AssignmentRow::assignmentVersion).orElse(0);
              if (cmd.expectedVersion() != null && cmd.expectedVersion() != current) {
                throw PmartException.versionConflict(
                    "배정이 이미 바뀌었습니다(기대 " + cmd.expectedVersion() + ", 현재 " + current + ").");
              }
              if (active.isPresent()) {
                AssignmentRow a = active.get();
                if (!a.mfcId().equals(cmd.mfcId())) {
                  throw new PmartException(ErrorCode.FORBIDDEN, "다른 센터의 작업입니다.");
                }
                if (a.workerId().equals(cmd.workerId())) {
                  return new Assigned(a, false, warnings);
                }
              }
              Instant at = clock.instant();
              active.ifPresent(a -> store.supersede(a.assignmentId(), at));
              AssignmentRow row =
                  new AssignmentRow(
                      "ASG-" + UUID.randomUUID(),
                      cmd.mfcId(),
                      cmd.taskId(),
                      cmd.taskType(),
                      cmd.workerId(),
                      store.maxVersion(cmd.taskId()) + 1,
                      "ACTIVE",
                      null,
                      supervisor,
                      cmd.reason(),
                      at,
                      cmd.temperatureZone());
              store.insert(row);
              publish(row, supervisor);
              log.info(
                  "task {} assigned to {} (v{}) at {} by {}",
                  row.taskId(),
                  row.workerId(),
                  row.assignmentVersion(),
                  row.mfcId(),
                  supervisor);
              return new Assigned(row, true, warnings);
            })
        .value();
  }

  /**
   * 배정 추천 확정(WK-10)의 한 항목. 호출자가 트랜잭션 안에서 그 작업에 활성 배정이 없음을 잠가 확인한 뒤 부른다. 사건 집계 키는 수동과 같은 {@code
   * MANUAL:<taskId>}(작업마다 assignmentVersion 순서를 한 파티션에서 지키려고), payload 에 planId·source PLAN.
   */
  AssignmentRow insertFromPlan(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      String planId,
      String by,
      Instant at) {
    AssignmentRow row =
        new AssignmentRow(
            "ASG-" + UUID.randomUUID(),
            mfcId,
            taskId,
            taskType,
            workerId,
            store.maxVersion(taskId) + 1,
            "ACTIVE",
            planId,
            by,
            "PLAN " + planId,
            at,
            null);
    store.insert(row);
    publish(row, by);
    return row;
  }

  /** 배정 작업자가 지금도 적격인가(null = 적격). 조회 때 계산 — 배정 뒤 정지·업무 변경을 시작 판정에 쓴다. */
  public @Nullable String eligibility(AssignmentRow r) {
    return workers.rejection(r.mfcId(), r.workerId(), r.taskType(), r.temperatureZone());
  }

  @Transactional(readOnly = true)
  public List<AssignmentRow> list(
      String mfcId,
      @Nullable String taskId,
      @Nullable String workerId,
      @Nullable String state,
      @Nullable LocalDate date) {
    requireReader(mfcId);
    String s = state == null ? "ACTIVE" : state;
    if (!STATES.contains(s)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 ACTIVE/SUPERSEDED/RELEASED.");
    }
    return store.list(mfcId, taskId, workerId, s, date);
  }

  private void publish(AssignmentRow r, String supervisor) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("taskId", r.taskId());
    item.put("workerId", r.workerId());
    item.put("assignmentVersion", r.assignmentVersion());
    item.put("taskType", r.taskType());
    item.put("assignmentId", r.assignmentId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("planVersion", r.assignmentVersion());
    payload.put("mfcId", r.mfcId());
    payload.put("assignments", List.of(item));
    if (r.planId() != null) {
      payload.put("planId", r.planId());
    }
    payload.put("source", r.planId() == null ? "MANUAL" : "PLAN");
    payload.put("publishedAt", r.assignedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "AssignmentPublished",
            1,
            "AssignmentPlan",
            "MANUAL:" + r.taskId(),
            r.assignmentVersion(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, supervisor),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  static String requireSupervisor(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.hasRole(SUPERVISOR_ROLE)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "이 센터의 " + SUPERVISOR_ROLE + " 만 작업을 배정할 수 있습니다.");
    }
    return p.subject();
  }

  static void requireReader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()) {
      return;
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }
}
