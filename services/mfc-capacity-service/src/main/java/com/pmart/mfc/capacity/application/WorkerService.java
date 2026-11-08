package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.WorkerRules;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.DutyRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.ShiftRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.StateChangeRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.WorkerRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업자·업무·근무(WK-02 입력, V5). 사용자 결정(2026-11-08): 감독자가 등록하고, 업무가 바뀌어도 로그인 정보는 그대로 — workerId(운영자 ID)는
 * 바꾸지 않는 열쇠이고 업무(작업 유형·온도대 자격)는 버전마다 따로 남는다. 상태(ACTIVE·SUSPENDED·LEFT)는 새 배정만 막고 로그인·센터 권한(auth)은
 * 건드리지 않는다. 업무·상태가 바뀌어도 기존 배정은 지우지 않고 맞지 않는 것을 알려 준다.
 */
@Service
public class WorkerService {

  public static final String WORKER_ALREADY_REGISTERED = "WORKER_ALREADY_REGISTERED";
  public static final String WORKER_NOT_FOUND = "WORKER_NOT_FOUND";
  public static final String SHIFT_NOT_FOUND = "SHIFT_NOT_FOUND";
  private static final Set<String> STATES = Set.of("ACTIVE", "SUSPENDED", "LEFT");
  private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

  public record View(WorkerRow worker, DutyRow duty) {}

  public record Detail(View view, List<DutyRow> duties, List<StateChangeRow> states) {}

  public record ChangeResult(View view, boolean changed, List<AssignmentRow> affected) {}

  private final WorkerStore store;
  private final AssignmentStore assignments;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public WorkerService(
      WorkerStore store, AssignmentStore assignments, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.assignments = assignments;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- 배정 판정 ----------

  /** WK-02 거절 코드, 적격이면 null. */
  public @Nullable String rejection(
      String mfcId, String workerId, String taskType, @Nullable String temperatureZone) {
    return WorkerRules.rejection(profile(mfcId, workerId), taskType, temperatureZone);
  }

  /** 지금 업무의 작업 유형(등록 안 됨이면 빈 집합). */
  public java.util.Set<String> taskTypes(String mfcId, String workerId) {
    var p = profile(mfcId, workerId);
    return p == null ? java.util.Set.of() : p.duty().taskTypes();
  }

  public boolean offShift(String mfcId, String workerId, Instant now) {
    List<Instant[]> s =
        store.shifts(mfcId, workerId, now.minusSeconds(86_400), now.plusSeconds(86_400)).stream()
            .map(x -> new Instant[] {x.startAt(), x.endAt()})
            .toList();
    // 앞뒤 하루에 근무가 하나도 없으면 근무를 쓰지 않는 작업자로 보고 판단하지 않는다
    return WorkerRules.offShift(s, now);
  }

  private WorkerRules.@Nullable Profile profile(String mfcId, String workerId) {
    return store
        .find(mfcId, workerId)
        .map(
            w -> {
              DutyRow d = store.duty(mfcId, workerId, w.dutyVersion());
              return new WorkerRules.Profile(
                  w.state(), new WorkerRules.Duty(d.dutyVersion(), d.taskTypes(), d.zones()));
            })
        .orElse(null);
  }

  // ---------- 등록·변경 ----------

  public View register(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      String displayName,
      Set<String> taskTypes,
      Set<String> zones) {
    String me = AssignmentService.requireSupervisor(mfcId);
    checkDuty(taskTypes, zones);
    if (workerId.isBlank() || displayName.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workerId·displayName 이 비었습니다.");
    }
    idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "registerWorker", me),
            List.of(mfcId, workerId, displayName, new TreeSet<>(taskTypes), new TreeSet<>(zones)),
            String.class,
            () -> {
              Instant at = clock.instant();
              if (!store.insert(mfcId, workerId, displayName, me, at)) {
                throw new PmartException(
                    WORKER_ALREADY_REGISTERED,
                    HttpStatus.CONFLICT,
                    workerId
                        + " 는 이미 "
                        + mfcId
                        + " 에 등록돼 있습니다 — 업무는 duty-changes, 복귀는 state-changes.",
                    false,
                    List.of());
              }
              store.insertDuty(mfcId, workerId, 1, taskTypes, zones, "등록", me, at);
              store.setState(mfcId, workerId, null, "ACTIVE", "등록", me, at);
              log.info("작업자 {} 등록 {} by {} (업무 {} {})", workerId, mfcId, me, taskTypes, zones);
              return workerId;
            })
        .value();
    return view(mfcId, workerId);
  }

  public ChangeResult changeDuty(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      @Nullable Integer expectedVersion,
      Set<String> taskTypes,
      Set<String> zones,
      String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    checkDuty(taskTypes, zones);
    boolean changed =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "changeWorkerDuty", me),
                List.of(
                    mfcId,
                    workerId,
                    new TreeSet<>(taskTypes),
                    new TreeSet<>(zones),
                    reason,
                    String.valueOf(expectedVersion)),
                Boolean.class,
                () -> {
                  WorkerRow w = lockOrThrow(mfcId, workerId);
                  if (expectedVersion != null && expectedVersion != w.dutyVersion()) {
                    throw PmartException.versionConflict(
                        "업무가 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + w.dutyVersion() + ").");
                  }
                  DutyRow cur = store.duty(mfcId, workerId, w.dutyVersion());
                  if (cur.taskTypes().equals(taskTypes) && cur.zones().equals(zones)) {
                    return false;
                  }
                  store.insertDuty(
                      mfcId,
                      workerId,
                      w.dutyVersion() + 1,
                      taskTypes,
                      zones,
                      reason,
                      me,
                      clock.instant());
                  log.info(
                      "작업자 {} 업무 v{} {} {} by {}",
                      workerId,
                      w.dutyVersion() + 1,
                      taskTypes,
                      zones,
                      me);
                  return true;
                })
            .value();
    return new ChangeResult(view(mfcId, workerId), changed, affected(mfcId, workerId));
  }

  public ChangeResult changeState(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      String state,
      String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    if (!STATES.contains(state)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "상태는 ACTIVE·SUSPENDED·LEFT.");
    }
    boolean changed =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "changeWorkerState", me),
                List.of(mfcId, workerId, state, reason),
                Boolean.class,
                () -> {
                  WorkerRow w = lockOrThrow(mfcId, workerId);
                  if (w.state().equals(state)) {
                    return false;
                  }
                  store.setState(mfcId, workerId, w.state(), state, reason, me, clock.instant());
                  log.info("작업자 {} {} → {} by {} ({})", workerId, w.state(), state, me, reason);
                  return true;
                })
            .value();
    return new ChangeResult(view(mfcId, workerId), changed, affected(mfcId, workerId));
  }

  /** 이 작업자의 ACTIVE 배정 중 지금 업무·상태로는 받을 수 없는 것. */
  private List<AssignmentRow> affected(String mfcId, String workerId) {
    WorkerRules.Profile p = profile(mfcId, workerId);
    return assignments.list(mfcId, null, workerId, "ACTIVE", null).stream()
        .filter(a -> WorkerRules.rejection(p, a.taskType(), a.temperatureZone()) != null)
        .toList();
  }

  // ---------- 조회 ----------

  @Transactional(readOnly = true)
  public List<View> list(String mfcId, @Nullable String state) {
    AssignmentService.requireReader(mfcId);
    return store.list(mfcId, state).stream()
        .map(w -> new View(w, store.duty(mfcId, w.workerId(), w.dutyVersion())))
        .toList();
  }

  @Transactional(readOnly = true)
  public Detail detail(String mfcId, String workerId) {
    AssignmentService.requireReader(mfcId);
    return new Detail(
        view(mfcId, workerId), store.duties(mfcId, workerId), store.stateChanges(mfcId, workerId));
  }

  private View view(String mfcId, String workerId) {
    WorkerRow w =
        store
            .find(mfcId, workerId)
            .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
    return new View(w, store.duty(mfcId, workerId, w.dutyVersion()));
  }

  // ---------- 근무 ----------

  public ShiftRow addShift(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      Instant start,
      Instant end,
      @Nullable String note) {
    String me = AssignmentService.requireSupervisor(mfcId);
    String bad = WorkerRules.invalidShift(start, end);
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "addWorkerShift", me),
                List.of(mfcId, workerId, start.toString(), end.toString(), String.valueOf(note)),
                String.class,
                () -> {
                  lockOrThrow(mfcId, workerId); // 같은 작업자 근무 등록을 줄 세운다
                  if (store.overlaps(mfcId, workerId, start, end)) {
                    throw new PmartException(
                        WorkerRules.SHIFT_OVERLAP,
                        HttpStatus.CONFLICT,
                        "같은 작업자의 다른 근무와 겹칩니다.",
                        false,
                        List.of());
                  }
                  return store
                      .insertShift(mfcId, workerId, start, end, note, me, clock.instant())
                      .shiftId();
                })
            .value();
    return store.shift(id).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<ShiftRow> shifts(
      String mfcId, String workerId, @Nullable Instant from, @Nullable Instant to) {
    AssignmentService.requireReader(mfcId);
    return store.shifts(mfcId, workerId, from, to);
  }

  @Transactional
  public ShiftRow cancelShift(String mfcId, String workerId, String shiftId, String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    ShiftRow s =
        store
            .shift(shiftId)
            .filter(x -> x.mfcId().equals(mfcId) && x.workerId().equals(workerId))
            .orElseThrow(() -> PmartException.notFound(SHIFT_NOT_FOUND, "근무가 없습니다: " + shiftId));
    if ("PLANNED".equals(s.state())) {
      store.cancelShift(shiftId, reason, me, clock.instant());
    }
    return store.shift(shiftId).orElseThrow();
  }

  private WorkerRow lockOrThrow(String mfcId, String workerId) {
    return store
        .lock(mfcId, workerId)
        .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
  }

  private static void checkDuty(Set<String> taskTypes, Set<String> zones) {
    String bad = WorkerRules.invalidDuty(taskTypes, zones);
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
  }
}
