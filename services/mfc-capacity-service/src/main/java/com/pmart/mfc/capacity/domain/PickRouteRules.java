package com.pmart.mfc.capacity.domain;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 피킹 방문 순서 규칙(2026-11-09 사용자 결정, ADR-0050 B2): MFC 문서 1장 8-3 원본 순서 {@code B → A → C → D → E → F → X
 * → Z} 를 설계 구역에 대 냉장(B) → 냉동(A) → 상온 C~H 그룹 순. 그룹 안은 거리 최단(엔진). 선반이 아닌 칸은 맨 뒤. 순수 계산.
 */
public final class PickRouteRules {

  private PickRouteRules() {}

  /** 그룹 순위(작을수록 먼저). 선반(STORAGE·PICKING)이 아니거나 구역을 모르면 맨 뒤. */
  public static int groupRank(
      @Nullable String zone, @Nullable String temperatureZone, @Nullable String areaCode) {
    boolean shelf = "STORAGE".equals(zone) || "PICKING".equals(zone);
    if (!shelf || temperatureZone == null) {
      return 100;
    }
    return switch (temperatureZone) {
      case "CHILLED" -> 0;
      case "FROZEN" -> 1;
      case "AMBIENT" -> {
        char g = areaCode == null || areaCode.isEmpty() ? 'Z' : areaCode.charAt(0);
        yield g >= 'C' && g <= 'H' ? 2 + (g - 'C') : 9; // C~H 그룹 순, 그 밖의 상온 구역(예 R 팔레트)은 상온 뒤
      }
      default -> 100;
    };
  }

  /** 가까운 칸부터(엔진을 못 쓸 때). from = 지금 위치 인덱스, nodes = 아직 안 간 칸 인덱스. */
  public static List<Integer> nearest(double[][] m, int from, List<Integer> nodes) {
    List<Integer> left = new ArrayList<>(nodes);
    List<Integer> out = new ArrayList<>();
    int cur = from;
    while (!left.isEmpty()) {
      int best = 0;
      for (int i = 1; i < left.size(); i++) {
        if (m[cur][left.get(i)] < m[cur][left.get(best)]) {
          best = i;
        }
      }
      cur = left.remove(best);
      out.add(cur);
    }
    return out;
  }

  /** 경로 길이(from 에서 차례로). */
  public static double length(double[][] m, int from, List<Integer> order) {
    double d = 0;
    int cur = from;
    for (int n : order) {
      d += m[cur][n];
      cur = n;
    }
    return d;
  }
}
