package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.TaskRules;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.ControlRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 센터 관리자 작업 조작(MFC 시뮬레이터 R-29 2단계, 사용자 결정 2026-11-20 — ADR-0077). 현장이 멈췄을 때 관리자가 작업을 멈춘다(WK-04 해결
 * 대기 BLOCKED).
 *
 * <ul>
 *   <li>누가: 그 센터 {@code MFC_SUPERVISOR} 또는 {@code OPERATOR_ADMIN}(모든 센터 — ADR-0076 관리자 판단 취소와 같은
 *       역할). 작업자는 PDA 문제 보고(결품)만.
 *   <li>작업 중지: READY·ASSIGNED·IN_PROGRESS → BLOCKED(block_kind SUPERVISOR). 담당·시작 점유·완료량·장바구니
 *       물건·재고·예약·배차는 그대로(WK-04 "고장만으로 주문 예약 해제 안 됨"). 결과를 기다리는 실적·열린 포장이 있으면 409
 *       EXECUTION_IN_FLIGHT(WK-04 사전 조건 "결과 불명 사건을 먼저 조회").
 * </ul>
 */
@Service
public class TaskControlService {

  public static final String SUPERVISOR_ROLE = "MFC_SUPERVISOR";
  public static final String ADMIN_ROLE = "OPERATOR_ADMIN";
  public static final String KIND_SUPERVISOR = "SUPERVISOR";

  static final Set<String> BLOCKABLE = Set.of("READY", "ASSIGNED", "IN_PROGRESS");

  private static final Logger log = LoggerFactory.getLogger(TaskControlService.class);

  /** 조작 결과(계약 TaskControl, 같은 키 재생). */
  public record Control(
      String controlId,
      String taskId,
      String action,
      String fromState,
      String toState,
      int taskVersion,
      String reason,
      String actorId,
      @Nullable String workerId,
      Instant at) {

    static Control of(ControlRow r) {
      return new Control(
          r.controlId(),
          r.taskId(),
          r.action(),
          r.fromState(),
          r.toState(),
          r.taskVersion(),
          r.reason(),
          r.actorId(),
          r.workerId(),
          r.at());
    }
  }

  private final TaskStore store;
  private final RequestIdempotency idempotency;
  private final FulfillmentEvents events;
  private final Clock clock;

  public TaskControlService(
      TaskStore store, RequestIdempotency idempotency, FulfillmentEvents events, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.clock = clock;
  }

  // ---------- 작업 중지 ----------

  public Control block(
      String taskId,
      String requestId,
      String sourceSystem,
      @Nullable String reason,
      @Nullable Integer expectedVersion) {
    TaskInfo t0 = task(taskId);
    String actor = requireSupervisor(t0.mfcId());
    String why = requireReason(reason);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("taskId", taskId);
    req.put("reason", why);
    req.put("expectedVersion", expectedVersion);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "blockTask", actor),
            req,
            Control.class,
            () -> {
              TaskInfo t = store.lock(taskId).orElseThrow();
              checkVersion(t, expectedVersion);
              if (!BLOCKABLE.contains(t.state())) {
                throw TaskService.conflict(
                    TaskRules.INVALID_STATE, "대기·배정·진행 중인 작업만 멈춥니다(지금 " + t.state() + ").");
              }
              requireNothingInFlight(t);
              Instant now = clock.instant();
              store.setBlocked(taskId, KIND_SUPERVISOR, "관리자 작업 중지: " + why, now);
              events.taskControlled(
                  t,
                  "BLOCKED",
                  t.state(),
                  t.version() + 1,
                  t.workerId(),
                  actor,
                  KIND_SUPERVISOR,
                  why,
                  now);
              ControlRow c =
                  record(
                      t, "BLOCK", "BLOCKED", t.version() + 1, why, actor, t.workerId(), requestId);
              log.warn("작업 {} 관리자 중지 by {} ({} → BLOCKED): {}", taskId, actor, t.state(), why);
              return Control.of(c);
            })
        .value();
  }

  /** 관리자 조작 기록(최근 순). 권한은 작업 상세와 같고, OPERATOR_ADMIN 은 다른 센터 작업도. */
  public List<Control> controls(String taskId) {
    TaskInfo t = task(taskId);
    requireReader(t.mfcId());
    return store.controls(taskId).stream().map(Control::of).toList();
  }

  // ---------- 내부 ----------

  ControlRow record(
      TaskInfo t,
      String action,
      String toState,
      int version,
      String reason,
      String actor,
      @Nullable String workerId,
      String requestId) {
    ControlRow c =
        new ControlRow(
            "TCT-" + UUID.randomUUID(),
            t.taskId(),
            action,
            t.state(),
            toState,
            version,
            reason,
            actor,
            workerId,
            requestId,
            null,
            clock.instant());
    store.insertControl(c);
    return c;
  }

  void requireNothingInFlight(TaskInfo t) {
    boolean openPack =
        "PACK".equals(t.taskType())
            && store.packages(t.shipmentId()).stream().anyMatch(x -> "OPEN".equals(x.state()));
    if (!store.openExecutions(t.taskId()).isEmpty() || openPack) {
      throw new PmartException(
          TaskRules.EXECUTION_IN_FLIGHT,
          HttpStatus.CONFLICT,
          "재고 결과를 기다리는 실적·포장이 있습니다 — 결과가 난 뒤 다시 합니다(결과 불명 먼저 확인, WK-04).",
          true,
          List.of());
    }
  }

  static void checkVersion(TaskInfo t, @Nullable Integer expectedVersion) {
    if (expectedVersion != null && expectedVersion != t.version()) {
      throw TaskService.conflict(
          TaskRules.VERSION_CONFLICT, "작업이 이미 바뀌었습니다(현재 버전 " + t.version() + ").");
    }
  }

  static String requireReason(@Nullable String reason) {
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유(reason)가 필요합니다.");
    }
    String r = reason.strip();
    if (r.length() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유는 200자까지입니다.");
    }
    return r;
  }

  TaskInfo task(String taskId) {
    return store
        .find(taskId)
        .orElseThrow(
            () -> PmartException.notFound(TaskService.TASK_NOT_FOUND, "작업이 없습니다: " + taskId));
  }

  /** 그 센터 MFC_SUPERVISOR 또는 OPERATOR_ADMIN(모든 센터). 반환 = 운영자 ID. */
  static String requireSupervisor(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (p.hasRole(ADMIN_ROLE) || (p.canAccessCenter(mfcId) && p.hasRole(SUPERVISOR_ROLE))) {
      return p.subject();
    }
    throw new PmartException(
        ErrorCode.FORBIDDEN, "이 센터의 " + SUPERVISOR_ROLE + " 또는 " + ADMIN_ROLE + " 만 할 수 있습니다.");
  }

  static void requireReader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService() || p.hasRole(ADMIN_ROLE)) {
      return;
    }
    TaskService.requireReader(mfcId);
  }
}
