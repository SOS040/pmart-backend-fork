package com.pmart.mfc.capacity.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.CumulativeConstraint;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.pmart.mfc.capacity.domain.PlanProblem;
import com.pmart.mfc.capacity.domain.PlanProblem.Item;
import com.pmart.mfc.capacity.domain.PlanProblem.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * OR-Tools CP-SAT 어댑터(WK-09). 모델: 작업마다 (적격 작업자별 선택 구간) 하나 또는 미배정, 작업자마다 NoOverlap(+ 막힌 구간), PACK 은
 * 패킹대 수만큼 Cumulative. 목적 = {@link PlanProblem} 가중치. 네이티브 라이브러리는 처음 쓸 때 한 번 올린다. 동시 계산은 2개까지.
 */
@Component
public class CpSatPlanner {

  private static volatile boolean loaded;
  private final Semaphore slots = new Semaphore(2);

  private static synchronized void load() {
    if (!loaded) {
      Loader.loadNativeLibraries();
      loaded = true;
    }
  }

  public Result solve(PlanProblem p, Duration timeLimit) {
    if (p.tasks().isEmpty()) {
      return new Result("NOT_RUN", List.of(), List.of(), 0, 0, 0, 0);
    }
    if (!slots.tryAcquire()) {
      return new Result("UNKNOWN", List.of(), List.of(), 0, 0, 0, 0); // 계산 자리 없음 — 수동 배정
    }
    try {
      load();
      return run(p, timeLimit);
    } finally {
      slots.release();
    }
  }

  private Result run(PlanProblem p, Duration timeLimit) {
    long t0 = System.nanoTime();
    CpModel m = new CpModel();
    int h = p.horizon();
    Map<String, List<IntervalVar>> byWorker = new HashMap<>();
    List<IntervalVar> packs = new ArrayList<>();
    LinearExprBuilder objective = LinearExpr.newBuilder();
    Map<String, LinearExprBuilder> load = new HashMap<>();
    record Choice(
        PlanProblem.Task task, String workerId, BoolVar present, IntVar start, IntVar late) {}
    List<Choice> choices = new ArrayList<>();
    Map<String, BoolVar> unassigned = new HashMap<>();

    for (PlanProblem.Worker w : p.workers()) {
      List<IntervalVar> ivs = byWorker.computeIfAbsent(w.workerId(), k -> new ArrayList<>());
      int i = 0;
      for (int[] b : w.busy()) {
        int s = Math.max(0, b[0]);
        int e = Math.min(h, b[1]);
        if (e > s) {
          ivs.add(m.newFixedInterval(s, e - s, "busy_" + w.workerId() + "_" + i++));
        }
      }
      load.put(w.workerId(), LinearExpr.newBuilder());
    }

    for (PlanProblem.Task t : p.tasks()) {
      BoolVar u = m.newBoolVar("u_" + t.taskId());
      unassigned.put(t.taskId(), u);
      LinearExprBuilder one = LinearExpr.newBuilder().add(u);
      int d = t.minutes();
      // 미배정은 늘 늦게라도 하는 것보다 비싸야 한다 — 급한 작업을 빼서 지연을 피하지 않게, 구간 끝에 끝낸 지연만큼 더한다
      long dropLate = t.due() == null ? 0 : Math.max(0, (long) h + d - t.due());
      objective.addTerm(u, PlanProblem.W_UNASSIGNED + PlanProblem.W_LATE * dropLate);
      if (d <= h) {
        for (PlanProblem.Worker w : p.workers()) {
          if (!w.can(t.taskType())) {
            continue;
          }
          String n = t.taskId() + "_" + w.workerId();
          BoolVar b = m.newBoolVar("x_" + n);
          IntVar s = m.newIntVar(0, h - d, "s_" + n);
          m.addEquality(s, 0).onlyEnforceIf(b.not());
          IntervalVar iv = m.newOptionalFixedSizeIntervalVar(s, d, b, "i_" + n);
          byWorker.get(w.workerId()).add(iv);
          if ("PACK".equals(t.taskType())) {
            packs.add(iv);
          }
          IntVar late =
              m.newIntVar(0, (long) h + Math.abs(t.due() == null ? 0 : t.due()), "l_" + n);
          if (t.due() != null) {
            // late ≥ start + d − due (배정됐을 때만)
            m.addGreaterOrEqual(late, LinearExpr.newBuilder().add(s).add(d - t.due()).build())
                .onlyEnforceIf(b);
          }
          m.addEquality(late, 0).onlyEnforceIf(b.not());
          objective.addTerm(late, PlanProblem.W_LATE);
          objective.add(s);
          one.add(b);
          load.get(w.workerId()).addTerm(b, d);
          choices.add(new Choice(t, w.workerId(), b, s, late));
        }
      }
      m.addEquality(one.build(), 1);
    }

    for (List<IntervalVar> ivs : byWorker.values()) {
      if (ivs.size() > 1) {
        m.addNoOverlap(ivs);
      }
    }
    if (!packs.isEmpty()) {
      CumulativeConstraint c = m.addCumulative(Math.max(1, p.packingStations()));
      for (IntervalVar iv : packs) {
        c.addDemand(iv, 1);
      }
    }
    IntVar maxLoad = m.newIntVar(0, h, "maxLoad");
    for (LinearExprBuilder l : load.values()) {
      m.addGreaterOrEqual(maxLoad, l.build());
    }
    objective.addTerm(maxLoad, PlanProblem.W_MAX_LOAD);
    m.minimize(objective.build());

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(Math.max(0.1, timeLimit.toMillis() / 1000.0));
    solver.getParameters().setNumWorkers(4);
    solver.getParameters().setRandomSeed(1); // 같은 입력이면 같은 추천(재현)
    CpSolverStatus st = solver.solve(m);
    long wall = (System.nanoTime() - t0) / 1_000_000;
    if (st != CpSolverStatus.OPTIMAL && st != CpSolverStatus.FEASIBLE) {
      return new Result(st.name(), List.of(), List.of(), 0, 0, 0, wall);
    }
    List<Item> items = new ArrayList<>();
    int lateSum = 0;
    for (Choice c : choices) {
      if (solver.booleanValue(c.present())) {
        int s = (int) solver.value(c.start());
        int late = (int) solver.value(c.late());
        lateSum += late;
        items.add(new Item(c.task().taskId(), c.workerId(), s, s + c.task().minutes(), late));
      }
    }
    items.sort(java.util.Comparator.comparingInt(Item::start).thenComparing(Item::taskId));
    List<String> left = new ArrayList<>();
    unassigned.forEach(
        (id, u) -> {
          if (solver.booleanValue(u)) {
            left.add(id);
          }
        });
    left.sort(String::compareTo);
    return new Result(
        st.name(),
        items,
        left,
        lateSum,
        (int) solver.value(maxLoad),
        (long) solver.objectiveValue(),
        wall);
  }
}
