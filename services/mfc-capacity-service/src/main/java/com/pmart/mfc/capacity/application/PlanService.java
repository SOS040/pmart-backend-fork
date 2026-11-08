package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.PlanProblem;
import com.pmart.mfc.capacity.domain.WorkerRules;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import com.pmart.mfc.capacity.infrastructure.CpSatPlanner;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import com.pmart.mfc.capacity.infrastructure.PlanStore;
import com.pmart.mfc.capacity.infrastructure.PlanStore.ItemRow;
import com.pmart.mfc.capacity.infrastructure.PlanStore.PlanRow;
import com.pmart.mfc.capacity.infrastructure.PlanStore.UnassignedRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 배정 추천(WK-09)·확정(WK-10). 추천은 배정을 바꾸지 않는다. 자동 확정(Q-08)은 없다 — 감독자가 확정한다.
 *
 * <ul>
 *   <li>계산: fulfillment 작업(READY = 대상, ASSIGNED·IN_PROGRESS = 그 담당의 앞 점유) + ACTIVE 작업자·지금 업무·근무 →
 *       {@link PlanProblem} → CP-SAT(트랜잭션 밖) → 저장. 근무를 등록한 작업자는 근무 안에서만, 근무가 없는 작업자는 구간 전체(배정 경고
 *       OFF_SHIFT 와 같은 기준). capacity 에 이미 활성 배정이 있는 READY 작업(투영 지연)은 빼고 앞 점유로 센다.
 *   <li>확정: 작업이 아직 READY·같은 version, 그 작업에 활성 배정 없음, 작업자가 아직 적격·같은 업무 버전 — 하나라도 아니면 아무것도 안 하고 STALE
 *       + 409 PLAN_STALE. 맞으면 한 트랜잭션에서 항목마다 배정 + AssignmentPublished.
 * </ul>
 */
@Service
public class PlanService {

  public static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";
  public static final String PLAN_STALE = "PLAN_STALE";
  static final int OFF_SHIFT_LOOKAROUND_HOURS = 24;

  private static final Logger log = LoggerFactory.getLogger(PlanService.class);

  public record PlanView(
      PlanRow plan,
      List<ItemRow> items,
      List<UnassignedRow> unassigned,
      List<AssignmentRow> assignments) {}

  /** 확정 결과(멱등 기록용). stale 이 비면 확정됨. */
  public record CommitOutcome(List<String> stale) {}

  private final PlanStore plans;
  private final WorkerStore workers;
  private final AssignmentStore assignments;
  private final AssignmentService assignmentService;
  private final FulfillmentClient fulfillment;
  private final CpSatPlanner planner;
  private final PlanProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;
  private final tools.jackson.databind.ObjectMapper mapper;

  public PlanService(
      PlanStore plans,
      WorkerStore workers,
      AssignmentStore assignments,
      AssignmentService assignmentService,
      FulfillmentClient fulfillment,
      CpSatPlanner planner,
      PlanProperties props,
      RequestIdempotency idempotency,
      Clock clock,
      tools.jackson.databind.ObjectMapper mapper) {
    this.mapper = mapper;
    this.plans = plans;
    this.workers = workers;
    this.assignments = assignments;
    this.assignmentService = assignmentService;
    this.fulfillment = fulfillment;
    this.planner = planner;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- WK-09 계산 ----------

  public PlanView create(String requestId, String sourceSystem, String mfcId, int horizon) {
    String me = AssignmentService.requireSupervisor(mfcId);
    if (horizon < 10 || horizon > 480) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "horizonMinutes 는 10~480 분입니다.");
    }
    Map<String, Integer> minutes = props.taskMinutes();
    if (minutes == null || minutes.isEmpty()) {
      throw PmartException.configRequired(PlanProperties.TASK_MINUTES_KEY);
    }
    Duration limit = props.timeLimit();
    if (limit == null || limit.isZero() || limit.isNegative()) {
      throw PmartException.configRequired(PlanProperties.TIME_LIMIT_KEY);
    }
    Integer stations = props.packingStations();
    if (stations == null || stations < 1) {
      throw PmartException.configRequired(PlanProperties.PACKING_STATIONS_KEY);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
    Instant end = now.plus(Duration.ofMinutes(horizon));

    // 입력 모으기(트랜잭션 밖)
    List<TaskView> all = fulfillment.tasks(mfcId, List.of("READY", "ASSIGNED", "IN_PROGRESS"));
    Map<String, AssignmentRow> active = new HashMap<>();
    for (AssignmentRow a : assignments.list(mfcId, null, null, "ACTIVE", null)) {
      active.put(a.taskId(), a);
    }
    Map<String, Integer> busyMinutes = new HashMap<>(); // 작업자 → 앞 점유 분
    List<PlanProblem.Task> tasks = new ArrayList<>();
    List<UnassignedRow> left = new ArrayList<>();
    Map<String, TaskView> byId = new LinkedHashMap<>();
    for (TaskView t : all) {
      Integer m = minutes.get(t.taskType());
      AssignmentRow a = active.get(t.taskId());
      String holder = a != null ? a.workerId() : t.workerId();
      if (!"READY".equals(t.state()) || a != null) {
        if (holder != null && m != null) {
          busyMinutes.merge(holder, m, Integer::sum);
        }
        continue;
      }
      byId.put(t.taskId(), t);
      if (m == null || m <= 0) {
        left.add(new UnassignedRow(t.taskId(), t.taskType(), "CONFIG_REQUIRED"));
        continue;
      }
      Integer due =
          t.promisedReadyAt() == null
              ? null
              : (int) Duration.between(now, t.promisedReadyAt()).toMinutes();
      tasks.add(new PlanProblem.Task(t.taskId(), t.taskType(), t.version(), m, due));
    }
    List<PlanProblem.Worker> ws = new ArrayList<>();
    List<String> unverified = new ArrayList<>();
    Map<String, Integer> dutyVersion = new HashMap<>();
    for (var w : workers.list(mfcId, "ACTIVE")) {
      var d = workers.duty(mfcId, w.workerId(), w.dutyVersion());
      dutyVersion.put(w.workerId(), d.dutyVersion());
      List<int[]> busy = new ArrayList<>();
      int pre = Math.min(horizon, busyMinutes.getOrDefault(w.workerId(), 0));
      if (pre > 0) {
        busy.add(new int[] {0, pre});
      }
      var shifts =
          workers.shifts(
              mfcId,
              w.workerId(),
              now.minus(Duration.ofHours(OFF_SHIFT_LOOKAROUND_HOURS)),
              end.plus(Duration.ofHours(OFF_SHIFT_LOOKAROUND_HOURS)));
      List<int[]> windows =
          shifts.stream()
              .map(
                  s ->
                      new int[] {
                        (int) Duration.between(now, s.startAt()).toMinutes(),
                        (int) Duration.between(now, s.endAt()).toMinutes()
                      })
              .toList();
      busy.addAll(PlanProblem.offShift(windows, horizon));
      if (windows.isEmpty()) {
        unverified.add(w.workerId()); // 근무 미등록 = 가용성 미확인(구간 전체로 계산, Q-09 미결)
      }
      ws.add(new PlanProblem.Worker(w.workerId(), d.dutyVersion(), d.taskTypes(), busy));
    }
    // 적격 작업자가 없는 작업은 미리 뺀다(이유가 다르다)
    List<PlanProblem.Task> solvable = new ArrayList<>();
    for (PlanProblem.Task t : tasks) {
      if (ws.stream().noneMatch(w -> w.can(t.taskType()))) {
        left.add(new UnassignedRow(t.taskId(), t.taskType(), "NO_ELIGIBLE_WORKER"));
      } else {
        solvable.add(t);
      }
    }
    var wt = props.resolvedWeights();
    PlanProblem.Result r =
        planner.solve(
            new PlanProblem(
                horizon,
                stations,
                solvable,
                ws,
                new PlanProblem.Weights(wt.unassigned(), wt.late(), wt.maxLoad())),
            limit);
    // 이 계산에 쓴 설정(임시 구현값)과 알려진 한계 — 계획마다 남긴다(2026-11-08 검토 #33·#34·#35·#37)
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("taskMinutes", new java.util.TreeMap<>(minutes));
    settings.put(
        "weights",
        Map.of("UNASSIGNED", wt.unassigned(), "LATE", wt.late(), "MAX_LOAD", wt.maxLoad()));
    settings.put("packingStations", stations);
    settings.put("timeLimitMs", limit.toMillis());
    settings.put("status", "TEMPORARY_IMPLEMENTATION_VALUES"); // 운영 정책 아님
    List<String> limits =
        new ArrayList<>(List.of("NO_TRAVEL_TIME", "ZONE_NOT_CHECKED", "FIXED_TASK_MINUTES"));
    if (!busyMinutes.isEmpty()) {
      limits.add(0, "IN_PROGRESS_FULL_DURATION");
    }
    settings.put("limitations", limits);
    String settingsJson = mapper.writeValueAsString(settings);
    String planId = "PLN-" + UUID.randomUUID();
    List<ItemRow> items = new ArrayList<>();
    if (r.solved()) {
      for (var i : r.items()) {
        TaskView t = byId.get(i.taskId());
        items.add(
            new ItemRow(
                i.taskId(),
                t.taskType(),
                t.version(),
                i.workerId(),
                dutyVersion.get(i.workerId()),
                now.plus(Duration.ofMinutes(i.start())),
                now.plus(Duration.ofMinutes(i.end())),
                i.late()));
      }
      for (String id : r.unassigned()) {
        left.add(new UnassignedRow(id, byId.get(id).taskType(), "NO_TIME"));
      }
    } else {
      for (PlanProblem.Task t : solvable) {
        left.add(new UnassignedRow(t.taskId(), t.taskType(), "NO_TIME"));
      }
    }
    String state = r.solved() ? "PROPOSED" : "NO_SOLUTION";
    PlanRow row =
        new PlanRow(
            planId,
            mfcId,
            state,
            r.status(),
            now,
            horizon,
            left.size(),
            r.lateMinutes(),
            r.maxLoadMinutes(),
            r.objective(),
            (int) r.wallMs(),
            List.of(),
            me,
            clock.instant(),
            null,
            null,
            settingsJson,
            unverified);
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createAssignmentPlan", me),
                List.of(mfcId, horizon),
                String.class,
                () -> {
                  plans.insert(row, items, left);
                  return planId;
                })
            .value();
    log.info(
        "배정 추천 {} {} {} — 항목 {}·미배정 {} ({} {}ms) by {}",
        id,
        mfcId,
        state,
        items.size(),
        left.size(),
        r.status(),
        r.wallMs(),
        me);
    return view(id);
  }

  // ---------- WK-10 확정 ----------

  public PlanView commit(String requestId, String sourceSystem, String planId) {
    PlanRow p0 = plans.find(planId).orElseThrow(() -> notFound(planId));
    String me = AssignmentService.requireSupervisor(p0.mfcId());
    if ("COMMITTED".equals(p0.state())) {
      return view(planId);
    }
    if (!"PROPOSED".equals(p0.state())) {
      throw PmartException.invalidState(
          "계획 " + planId + " 는 " + p0.state() + " 라 확정할 수 없습니다(PROPOSED 만) — 다시 계산합니다.");
    }
    List<ItemRow> items = plans.items(planId);
    // 최신 작업 상태(트랜잭션 밖, 1 hop)
    Map<String, TaskView> now = new HashMap<>();
    for (TaskView t : fulfillment.tasks(p0.mfcId(), List.of("READY"))) {
      now.put(t.taskId(), t);
    }
    CommitOutcome out =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "commitAssignmentPlan", me),
                List.of(planId),
                CommitOutcome.class,
                () -> {
                  PlanRow p = plans.lock(planId).orElseThrow(() -> notFound(planId));
                  if ("COMMITTED".equals(p.state())) {
                    return new CommitOutcome(List.of());
                  }
                  if (!"PROPOSED".equals(p.state())) {
                    return new CommitOutcome(p.staleReasons());
                  }
                  List<String> stale = new ArrayList<>();
                  Map<String, Optional<AssignmentRow>> locked = new HashMap<>();
                  for (ItemRow i : items) {
                    TaskView t = now.get(i.taskId());
                    if (t == null || t.version() != i.taskVersion()) {
                      stale.add("TASK_CHANGED:" + i.taskId());
                      continue;
                    }
                    Optional<AssignmentRow> a = assignments.lockActive(i.taskId());
                    locked.put(i.taskId(), a);
                    if (a.isPresent()) {
                      stale.add("TASK_ALREADY_ASSIGNED:" + i.taskId());
                    }
                    var w = workers.find(p.mfcId(), i.workerId());
                    String reject =
                        w.isEmpty()
                            ? WorkerRules.WORKER_NOT_REGISTERED
                            : WorkerRules.rejection(
                                profile(p.mfcId(), w.get()), i.taskType(), null);
                    if (reject != null || w.get().dutyVersion() != i.dutyVersion()) {
                      stale.add("WORKER_CHANGED:" + i.workerId());
                    }
                  }
                  if (!stale.isEmpty()) {
                    List<String> uniq = stale.stream().distinct().toList();
                    plans.markStale(planId, uniq);
                    log.info("배정 추천 {} STALE: {}", planId, uniq);
                    return new CommitOutcome(uniq);
                  }
                  Instant at = clock.instant();
                  for (ItemRow i : items) {
                    assignmentService.insertFromPlan(
                        p.mfcId(), i.taskId(), i.taskType(), i.workerId(), planId, me, at);
                  }
                  plans.markCommitted(planId, me, at);
                  log.info("배정 추천 {} 확정 {}건 by {}", planId, items.size(), me);
                  return new CommitOutcome(List.of());
                })
            .value();
    if (!out.stale().isEmpty()) {
      throw new PmartException(
          PLAN_STALE,
          HttpStatus.CONFLICT,
          "계산 뒤 작업·작업자가 바뀌어 확정하지 않았습니다 — 다시 계산합니다: " + String.join(", ", out.stale()),
          false,
          List.of());
    }
    return view(planId);
  }

  // ---------- 조회 ----------

  public PlanView get(String planId) {
    PlanRow p = plans.find(planId).orElseThrow(() -> notFound(planId));
    AssignmentService.requireReader(p.mfcId());
    return view(planId);
  }

  private PlanView view(String planId) {
    PlanRow p = plans.find(planId).orElseThrow(() -> notFound(planId));
    List<AssignmentRow> made =
        "COMMITTED".equals(p.state())
            ? assignments.list(p.mfcId(), null, null, "ACTIVE", null).stream()
                .filter(a -> planId.equals(a.planId()))
                .toList()
            : List.of();
    return new PlanView(p, plans.items(planId), plans.unassigned(planId), made);
  }

  private WorkerService workerService;

  @org.springframework.beans.factory.annotation.Autowired
  void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  /** 추천도 배정과 같은 조건 — 유효 업무 = 업무 ∩ 유효 자격(일일크루는 오늘 등록한 날만, 작업자 쪽 2단계). */
  private WorkerRules.Profile profile(String mfcId, WorkerStore.WorkerRow w) {
    var d = workers.duty(mfcId, w.workerId(), w.dutyVersion());
    return new WorkerRules.Profile(
        w.state(),
        new WorkerRules.Duty(
            d.dutyVersion(), workerService.taskTypes(mfcId, w.workerId()), d.zones()));
  }

  private static PmartException notFound(@Nullable String planId) {
    return PmartException.notFound(PLAN_NOT_FOUND, "배정 추천이 없습니다: " + planId);
  }
}
