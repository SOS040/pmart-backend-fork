package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.TaskRules;
import com.pmart.mfc.fulfillment.domain.TaskRules.ActiveAssignment;
import com.pmart.mfc.fulfillment.infrastructure.CapacityClient;
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
  private final CapacityClient capacity;
  private final Clock clock;

  public TaskControlService(
      TaskStore store,
      RequestIdempotency idempotency,
      FulfillmentEvents events,
      CapacityClient capacity,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.capacity = capacity;
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

  // ---------- 막힘 해제 ----------

  /** 실물 확인 표시가 없음(422, 0.29.0). */
  public static final String PHYSICAL_CHECK_REQUIRED = "PHYSICAL_CHECK_REQUIRED";

  /** 결품으로 막힌 작업 — 결품 사가가 푼다(409, 0.29.0). */
  public static final String BLOCKED_BY_SHORTAGE = "BLOCKED_BY_SHORTAGE";

  /**
   * 관리자 중지 해제(WK-05): capacity 활성 배정이 있고 그 작업자가 지금 맡을 수 있으면 ASSIGNED(그 담당, 시작 점유는 지움 — 재개는 WK-03
   * 시작), 아니면 capacity 배정을 풀고 READY. capacity 조회·해제는 트랜잭션 밖(실패면 503, 작업은 그대로). 같은 키 재전송은 capacity 를
   * 다시 부르지 않는다.
   */
  public Control unblock(
      String taskId,
      String requestId,
      String sourceSystem,
      @Nullable String reason,
      @Nullable Boolean cartChecked,
      @Nullable Integer expectedVersion) {
    TaskInfo t0 = task(taskId);
    String actor = requireSupervisor(t0.mfcId());
    String why = requireReason(reason);
    if (!Boolean.TRUE.equals(cartChecked)) {
      throw new PmartException(
          PHYSICAL_CHECK_REQUIRED,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "장바구니·집은 물건을 실물로 확인한 뒤(cartChecked: true) 해제합니다.",
          false,
          List.of());
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("taskId", taskId);
    req.put("reason", why);
    req.put("cartChecked", true);
    req.put("expectedVersion", expectedVersion);
    // 같은 키로 이미 처리됐으면 capacity 를 다시 부르지 않고 기록을 재생한다
    ActiveAssignment[] keep = {null};
    if (store.findControl(taskId, requestId).isEmpty()) {
      checkVersion(t0, expectedVersion);
      requireSupervisorBlock(t0);
      ActiveAssignment a = capacity.active(t0.mfcId(), taskId); // 실패면 503 — 기록 없음
      if (a != null && a.workerRejection() == null) {
        keep[0] = a;
      } else if (a != null) {
        // 정지·퇴사·업무 변경 — 배정을 풀어 누구나 받게(실패면 503, 작업은 그대로 BLOCKED)
        capacity.release(taskId, a.workerId(), "막힘 해제(" + a.workerRejection() + "): " + why);
      }
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "unblockTask", actor),
            req,
            Control.class,
            () -> {
              TaskInfo t = store.lock(taskId).orElseThrow();
              checkVersion(t, expectedVersion);
              requireSupervisorBlock(t);
              Instant now = clock.instant();
              ActiveAssignment a = keep[0];
              String to;
              String worker;
              if (a != null) {
                if (!a.workerId().equals(t.workerId())) {
                  // 배정 사건보다 먼저 왔다 — capacity 정본으로 투영을 맞춘다(더 새 버전만)
                  store.applyAssignment(taskId, a.workerId(), null, a.assignmentVersion(), now);
                }
                store.setState(taskId, "ASSIGNED", null, true, now);
                to = "ASSIGNED";
                worker = a.workerId();
              } else {
                store.releaseToReady(taskId, now);
                to = "READY";
                worker = null;
              }
              events.taskControlled(
                  t, to, "BLOCKED", t.version() + 1, worker, actor, null, why, now);
              ControlRow c =
                  record(t, "UNBLOCK", to, t.version() + 1, why, actor, worker, requestId);
              log.info("작업 {} 막힘 해제 by {} → {} (담당 {}): {}", taskId, actor, to, worker, why);
              return Control.of(c);
            })
        .value();
  }

  static void requireSupervisorBlock(TaskInfo t) {
    if (!"BLOCKED".equals(t.state())) {
      throw TaskService.conflict(
          TaskRules.INVALID_STATE, "막힌(BLOCKED) 작업만 해제합니다(지금 " + t.state() + ").");
    }
    if (!KIND_SUPERVISOR.equals(t.blockKind())) {
      throw TaskService.conflict(
          BLOCKED_BY_SHORTAGE, "결품·예약 보류로 막힌 작업입니다 — 결품 처리가 풉니다(" + t.blockReason() + ").");
    }
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
