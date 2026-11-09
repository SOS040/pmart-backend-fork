package com.pmart.mfc.location.domain;

import java.util.List;

/**
 * 칸 사이 걷는 거리(0.7.0, ADR-0050) — 1차는 도면 좌표 직각 거리(|Δx| + |Δz|, 미터, 소수 1자리). 벽·문·통로는 아직 보지 않는다. 순수 계산.
 */
public final class Distances {

  public static final String METHOD = "MANHATTAN_V1";
  public static final String WALLS_NOT_CONSIDERED = "WALLS_NOT_CONSIDERED";

  private Distances() {}

  /** 좌표 목록 → 거리표. xs·zs 는 같은 길이. */
  public static double[][] manhattan(List<Double> xs, List<Double> zs) {
    int n = xs.size();
    double[][] m = new double[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        double d = Math.abs(xs.get(i) - xs.get(j)) + Math.abs(zs.get(i) - zs.get(j));
        double r = Math.round(d * 10) / 10.0;
        m[i][j] = r;
        m[j][i] = r;
      }
    }
    return m;
  }
}
