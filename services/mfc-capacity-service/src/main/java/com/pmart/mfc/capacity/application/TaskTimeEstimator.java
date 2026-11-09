package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.domain.PickRouteRules;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView;
import com.pmart.mfc.capacity.infrastructure.LocationClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 배정 추천의 작업 시간 추정(0.11.0, 사용자 결정 2026-11-09 — 실데이터가 없어 도면·임시 처리값으로).
 *
 * <ul>
 *   <li>PICK·PACK: 유형 분(문서 기준 PICK 7·PACK 4)을 이번 계산의 같은 유형 평균으로 두고, 작업마다 (걷는 거리 ÷ 걷는 속도 + 줄·개수 처리
 *       초) 의 비율로 늘이고 줄인다(0.5~2배). PICK 걷는 길 = 출발(장바구니 보관 자리) → 후보 칸(냉장 → 냉동 → 상온 C~H, 그룹 안 가까운 칸부터)
 *       → 포장 위치.
 *   <li>MOVE·REPLENISH: 출발 → 출발 칸 → 도착 칸, RETURN: 포장 위치 → 추천 칸 — 걷는 시간 + 처리 초를 그대로 분으로(최소 1분).
 * </ul>
 *
 * 작업 사이 이동은 넣지 않는다(피킹은 늘 장바구니 자리에서 출발 — 거의 일정). 추정 원재료는 (작업, 버전) 마다 기억한다.
 */
@Component
public class TaskTimeEstimator {

  private static final Logger log = LoggerFactory.getLogger(TaskTimeEstimator.class);
  static final int MAX_FETCH_PER_PLAN = 80; // 한 계산에서 새로 상세를 읽는 작업 수(그 밖은 유형 분 — 부분 추정)
  static final double MIN_RATIO = 0.5;
  static final double MAX_RATIO = 2.0;

  /** 작업 하나의 원재료: 걷는 거리(m)와 처리 초. */
  record Raw(double walkMeters, double handlingSec) {}

  /** 결과: 작업 → 분, partial = 추정 못 한 작업이 있음. */
  public record Estimate(
      Map<String, Integer> minutes, boolean partial, Map<String, Object> settings) {}

  private final FulfillmentClient fulfillment;
  private final LocationClient locations;
  private final PlanProperties props;
  private final Map<String, Raw> cache = new ConcurrentHashMap<>();

  public TaskTimeEstimator(
      FulfillmentClient fulfillment, LocationClient locations, PlanProperties props) {
    this.fulfillment = fulfillment;
    this.locations = locations;
    this.props = props;
  }

  /** 설정(걷는 속도·처리 초·출발·포장 위치)이 다 있으면 추정을 쓴다. */
  public boolean enabled() {
    PlanProperties.Travel t = props.travel();
    return t != null
        && t.walkSpeedMps() != null
        && t.walkSpeedMps() > 0
        && t.scanSecPerLine() != null
        && t.secPerUnit() != null
        && t.packLocation() != null
        && props.pickRouteStart() != null
        && props.mfcLocationBaseUrl() != null;
  }

  public Estimate estimate(
      String mfcId,
      List<TaskView> ready,
      Map<String, MoveView> moves,
      Map<String, Integer> baseMinutes) {
    PlanProperties.Travel cfg = java.util.Objects.requireNonNull(props.travel());
    String start = java.util.Objects.requireNonNull(props.pickRouteStart());
    String pack = java.util.Objects.requireNonNull(cfg.packLocation());
    boolean partial = false;
    int fetched = 0;
    // 1) 작업마다 칸·처리량 모으기
    Map<String, List<String>> pickCodes = new LinkedHashMap<>(); // PICK: 후보 칸
    Map<String, String[]> legs = new LinkedHashMap<>(); // MOVE·REPLENISH·RETURN: 지나는 칸 순서
    Map<String, double[]> handling = new LinkedHashMap<>(); // 작업 → {줄, 개수}
    Map<String, Raw> raw = new HashMap<>();
    for (TaskView t : ready) {
      String key = t.taskId() + "|" + t.version();
      Raw c = cache.get(key);
      if (c != null) {
        raw.put(t.taskId(), c);
        continue;
      }
      try {
        switch (t.taskType()) {
          case "PICK", "RETURN" -> {
            if (fetched >= MAX_FETCH_PER_PLAN) {
              partial = true;
              continue;
            }
            fetched++;
            JsonNode d = fulfillment.taskDetail(t.taskId());
            int lines = 0;
            int units = 0;
            List<String> codes = new ArrayList<>();
            String retFrom = null;
            String retTo = null;
            for (JsonNode l : d.path("lines")) {
              boolean any = false;
              for (JsonNode p : l.path("picks")) {
                int q = p.path("remainingQty").asInt(0);
                if (q <= 0) {
                  continue;
                }
                any = true;
                units += q;
                String from = fromLocation(p.path("locations"));
                if (from != null) {
                  codes.add(from);
                  retFrom = retFrom == null ? from : retFrom;
                }
                if (p.path("suggestedToLocations").isArray()
                    && !p.path("suggestedToLocations").isEmpty()
                    && retTo == null) {
                  retTo = p.path("suggestedToLocations").path(0).asString(null);
                }
              }
              lines += any ? 1 : 0;
            }
            if ("PICK".equals(t.taskType())) {
              if (codes.isEmpty()) {
                partial = true;
                continue;
              }
              pickCodes.put(t.taskId(), codes);
            } else {
              if (retFrom == null || retTo == null) {
                partial = true;
                continue;
              }
              legs.put(t.taskId(), new String[] {retFrom, retTo});
            }
            handling.put(t.taskId(), new double[] {lines, units});
          }
          case "PACK" -> {
            int units =
                t.remainingQty() != null
                    ? t.remainingQty()
                    : t.plannedQty() == null ? 0 : t.plannedQty();
            raw.put(t.taskId(), new Raw(0, units * (double) cfg.secPerUnit()));
          }
          case "MOVE", "REPLENISH" -> {
            MoveView mv = moves.get(t.taskId());
            if (mv == null || mv.fromLocation() == null || mv.toLocation() == null) {
              partial = true;
              continue;
            }
            legs.put(t.taskId(), new String[] {start, mv.fromLocation(), mv.toLocation()});
            int q = Math.max(0, mv.qty() - mv.pickedQty());
            handling.put(t.taskId(), new double[] {1, q});
          }
          default -> partial = true;
        }
      } catch (RuntimeException e) {
        partial = true;
        log.warn("작업 {} 시간 추정 재료 못 읽음 — 유형 분: {}", t.taskId(), e.getMessage());
      }
    }
    // 2) 거리표(작업 묶음마다 300칸 이하)
    List<String> ids = new ArrayList<>();
    ids.addAll(pickCodes.keySet());
    ids.addAll(legs.keySet());
    int i = 0;
    while (i < ids.size()) {
      Set<String> codes = new LinkedHashSet<>(List.of(start, pack));
      List<String> batch = new ArrayList<>();
      while (i < ids.size()) {
        String id = ids.get(i);
        List<String> need = pickCodes.containsKey(id) ? pickCodes.get(id) : List.of(legs.get(id));
        Set<String> next = new LinkedHashSet<>(codes);
        next.addAll(need);
        if (next.size() > 300 && !batch.isEmpty()) {
          break;
        }
        codes = next;
        batch.add(id);
        i++;
      }
      try {
        LocationClient.Table tb = locations.distances(mfcId, new ArrayList<>(codes));
        for (String id : batch) {
          double[] h = handling.get(id);
          double handlingSec = h[0] * cfg.scanSecPerLine() + h[1] * cfg.secPerUnit();
          Double walk =
              pickCodes.containsKey(id)
                  ? pickWalk(tb, start, pickCodes.get(id), pack)
                  : legWalk(tb, legs.get(id));
          if (walk == null) {
            partial = true;
            continue;
          }
          raw.put(id, new Raw(walk, handlingSec));
        }
      } catch (RuntimeException e) {
        partial = true;
        log.warn("거리표 못 읽음 — 이 묶음은 유형 분: {}", e.getMessage());
      }
    }
    // 3) 분으로
    for (TaskView t : ready) {
      Raw r = raw.get(t.taskId());
      if (r != null) {
        cache.put(t.taskId() + "|" + t.version(), r);
      }
    }
    Map<String, Integer> minutes = new HashMap<>();
    for (String type : List.of("PICK", "PACK")) {
      Integer base = baseMinutes.get(type);
      List<TaskView> same =
          ready.stream()
              .filter(t -> type.equals(t.taskType()) && raw.containsKey(t.taskId()))
              .toList();
      if (base == null || same.isEmpty()) {
        continue;
      }
      double mean =
          same.stream()
              .mapToDouble(t -> seconds(raw.get(t.taskId()), cfg.walkSpeedMps()))
              .average()
              .orElse(0);
      for (TaskView t : same) {
        double ratio = mean <= 0 ? 1.0 : seconds(raw.get(t.taskId()), cfg.walkSpeedMps()) / mean;
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
        minutes.put(t.taskId(), Math.max(1, (int) Math.round(base * ratio)));
      }
    }
    for (TaskView t : ready) {
      if (List.of("MOVE", "REPLENISH", "RETURN").contains(t.taskType())
          && raw.containsKey(t.taskId())) {
        minutes.put(
            t.taskId(),
            Math.max(1, (int) Math.ceil(seconds(raw.get(t.taskId()), cfg.walkSpeedMps()) / 60)));
      }
    }
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("walkSpeedMps", cfg.walkSpeedMps());
    settings.put("scanSecPerLine", cfg.scanSecPerLine());
    settings.put("secPerUnit", cfg.secPerUnit());
    settings.put("start", start);
    settings.put("packLocation", pack);
    settings.put("ratioRange", List.of(MIN_RATIO, MAX_RATIO));
    return new Estimate(minutes, partial, settings);
  }

  static double seconds(Raw r, double walkSpeed) {
    return r.walkMeters() / walkSpeed + r.handlingSec();
  }

  /** 배정 칸 우선, 없으면 첫 칸. */
  static @Nullable String fromLocation(JsonNode locations) {
    String first = null;
    for (JsonNode x : locations) {
      String code = x.path("locationCode").asString(null);
      if (first == null) {
        first = code;
      }
      if (x.path("allocatedQty").asInt(0) > 0) {
        return code;
      }
    }
    return first;
  }

  /** 출발 → 후보 칸(그룹 순, 그룹 안 가까운 칸부터) → 포장 위치. 좌표 없는 칸은 건너뜀, 출발·포장 좌표가 없으면 null. */
  static @Nullable Double pickWalk(
      LocationClient.Table tb, String start, List<String> codes, String pack) {
    int s = tb.codes().indexOf(start);
    int p = tb.codes().indexOf(pack);
    if (s < 0 || p < 0) {
      return null;
    }
    TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
    for (String c : new LinkedHashSet<>(codes)) {
      int idx = tb.codes().indexOf(c);
      if (idx < 0) {
        continue;
      }
      LocationClient.Attr a = tb.attrs().get(c);
      int rank =
          a == null ? 100 : PickRouteRules.groupRank(a.zone(), a.temperatureZone(), a.areaCode());
      groups.computeIfAbsent(rank, k -> new ArrayList<>()).add(idx);
    }
    double d = 0;
    int cur = s;
    for (List<Integer> nodes : groups.values()) {
      List<Integer> path = PickRouteRules.nearest(tb.meters(), cur, nodes);
      d += PickRouteRules.length(tb.meters(), cur, path);
      cur = path.get(path.size() - 1);
    }
    return d + tb.meters()[cur][p];
  }

  /** 칸을 차례로 지나는 거리. 좌표 없는 칸이 있으면 null. */
  static @Nullable Double legWalk(LocationClient.Table tb, String[] legs) {
    double d = 0;
    int prev = -1;
    for (String c : legs) {
      int idx = tb.codes().indexOf(c);
      if (idx < 0) {
        return null;
      }
      if (prev >= 0) {
        d += tb.meters()[prev][idx];
      }
      prev = idx;
    }
    return d;
  }
}
