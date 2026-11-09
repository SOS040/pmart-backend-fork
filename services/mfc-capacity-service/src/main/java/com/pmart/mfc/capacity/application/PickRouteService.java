package com.pmart.mfc.capacity.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.PickRouteRules;
import com.pmart.mfc.capacity.infrastructure.LocationClient;
import com.pmart.mfc.capacity.infrastructure.OrToolsRouter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 피킹 방문 순서(ADR-0050 B2, 2026-11-09 사용자 결정): 냉장 → 냉동 → 상온 C~H 그룹 순, 그룹 안은 OR-Tools 경로로 최단(못 풀면 가까운
 * 칸부터), 출발점 = 장바구니 보관 자리(설정). 그룹 사이는 앞 그룹 마지막 칸에서 이어 간다.
 */
@Service
public class PickRouteService {

  public static final String START_KEY = "pmart.capacity.plan.pick-route-start";
  static final long GROUP_TIME_LIMIT_MS = 300; // 그룹 하나당(임시 구현값) — 피킹 시작 응답을 늦추지 않게

  private static final Logger log = LoggerFactory.getLogger(PickRouteService.class);

  /** 결과. method = ROUTING / NEAREST_FALLBACK. */
  public record Route(
      List<String> ordered,
      String startLocation,
      String method,
      double totalMeters,
      List<String> missing,
      List<String> limitations) {}

  private final LocationClient locations;
  private final OrToolsRouter router;
  private final PlanProperties props;

  public PickRouteService(LocationClient locations, OrToolsRouter router, PlanProperties props) {
    this.locations = locations;
    this.router = router;
    this.props = props;
  }

  public Route route(String mfcId, List<String> codes) {
    String start = props.pickRouteStart();
    if (start == null || start.isBlank()) {
      throw PmartException.configRequired(START_KEY);
    }
    if (codes.isEmpty() || codes.size() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "locationCodes 는 1~200개입니다.");
    }
    List<String> uniq = new ArrayList<>(new LinkedHashSet<>(codes));
    List<String> ask = new ArrayList<>(uniq);
    if (!ask.contains(start)) {
      ask.add(0, start);
    }
    LocationClient.Table t = locations.distances(mfcId, ask);
    int from = t.codes().indexOf(start);
    List<String> limitations = new ArrayList<>(List.of("WALLS_NOT_CONSIDERED"));
    if (from < 0) {
      // 출발 칸 좌표가 없으면 첫 그룹 첫 칸에서 출발한 것으로
      limitations.add("START_POSITION_UNKNOWN");
    }
    // 그룹별로 칸을 모은다(그룹 순위 → 칸 인덱스)
    TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
    List<String> tail = new ArrayList<>();
    for (String c : uniq) {
      int i = t.codes().indexOf(c);
      if (i < 0) {
        tail.add(c); // 좌표 없음·없는 칸
        continue;
      }
      LocationClient.Attr a = t.attrs().get(c);
      int rank =
          a == null ? 100 : PickRouteRules.groupRank(a.zone(), a.temperatureZone(), a.areaCode());
      if (rank >= 100) {
        tail.add(c);
        continue;
      }
      groups.computeIfAbsent(rank, k -> new ArrayList<>()).add(i);
    }
    List<String> ordered = new ArrayList<>();
    String method = "ROUTING";
    int cur = from;
    double total = 0;
    for (Map.Entry<Integer, List<Integer>> g : groups.entrySet()) {
      List<Integer> nodes = g.getValue();
      int origin = cur >= 0 ? cur : nodes.get(0); // 출발 칸 좌표를 모르면 첫 그룹 첫 칸에서
      List<Integer> path = router.shortestOpenPath(t.meters(), origin, nodes, GROUP_TIME_LIMIT_MS);
      if (path == null) {
        if (nodes.size() > 1) {
          method = "NEAREST_FALLBACK"; // 엔진을 못 쓰거나 시간 안에 못 풂 — 가까운 칸부터
        }
        path = PickRouteRules.nearest(t.meters(), origin, nodes);
      }
      total += PickRouteRules.length(t.meters(), origin, path);
      for (int i : path) {
        ordered.add(t.codes().get(i));
      }
      cur = path.isEmpty() ? cur : path.get(path.size() - 1);
    }
    tail.sort(String::compareTo);
    ordered.addAll(tail);
    if (!tail.isEmpty()) {
      limitations.add("NON_SHELF_OR_NO_POSITION_LAST");
    }
    log.info("피킹 순서 {} 칸 {} → {} ({} m)", mfcId, uniq.size(), method, Math.round(total));
    return new Route(
        ordered, start, method, Math.round(total * 10) / 10.0, t.missing(), limitations);
  }
}
