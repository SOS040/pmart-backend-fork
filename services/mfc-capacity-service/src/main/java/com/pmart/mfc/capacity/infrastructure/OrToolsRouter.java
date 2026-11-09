package com.pmart.mfc.capacity.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * OR-Tools 경로(Routing) 어댑터(ADR-0050 B2) — 열린 경로: from 에서 출발해 nodes 를 한 번씩 들르고 아무 데서나 끝나는 가장 짧은 순서.
 * 거리는 0.1 m 를 정수(×10)로. 엔진을 쓸 수 없거나(네이티브 로드 실패) 시간 안에 못 풀면 null(호출자가 가까운 칸부터로 대신).
 */
@Component
public class OrToolsRouter {

  private static volatile boolean loaded;

  private static synchronized void load() {
    if (!loaded) {
      Loader.loadNativeLibraries();
      loaded = true;
    }
  }

  public @Nullable List<Integer> shortestOpenPath(
      double[][] m, int from, List<Integer> nodes, long timeLimitMs) {
    if (nodes.size() <= 1) {
      return new ArrayList<>(nodes);
    }
    try {
      load();
    } catch (Throwable e) { // UnsatisfiedLinkError 등 — 엔진 없이
      return null;
    }
    // 노드: 0 = 출발, 1..n = 칸, n+1 = 가짜 끝(어느 칸에서든 0)
    int n = nodes.size();
    int[] idx = new int[n + 2];
    idx[0] = from;
    for (int i = 0; i < n; i++) {
      idx[i + 1] = nodes.get(i);
    }
    int end = n + 1;
    RoutingIndexManager manager = new RoutingIndexManager(n + 2, 1, new int[] {0}, new int[] {end});
    RoutingModel routing = new RoutingModel(manager);
    int transit =
        routing.registerTransitCallback(
            (long a, long b) -> {
              int i = manager.indexToNode(a);
              int j = manager.indexToNode(b);
              if (j == end || i == end) {
                return i == 0 && j == end ? 1_000_000L : 0L; // 출발에서 바로 끝으로는 가지 않게
              }
              return Math.round(m[idx[i]][idx[j]] * 10);
            });
    routing.setArcCostEvaluatorOfAllVehicles(transit);
    RoutingSearchParameters params =
        main.defaultRoutingSearchParameters().toBuilder()
            .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
            .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
            .setTimeLimit(
                Duration.newBuilder()
                    .setSeconds(timeLimitMs / 1000)
                    .setNanos((int) (timeLimitMs % 1000) * 1_000_000))
            .build();
    Assignment sol = routing.solveWithParameters(params);
    if (sol == null) {
      return null;
    }
    List<Integer> out = new ArrayList<>();
    long index = sol.value(routing.nextVar(routing.start(0)));
    while (!routing.isEnd(index)) {
      int node = manager.indexToNode(index);
      if (node >= 1 && node <= n) {
        out.add(idx[node]);
      }
      index = sol.value(routing.nextVar(index));
    }
    return out.size() == n ? out : null;
  }
}
