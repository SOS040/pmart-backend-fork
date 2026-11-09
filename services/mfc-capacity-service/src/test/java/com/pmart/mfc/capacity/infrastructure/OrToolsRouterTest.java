package com.pmart.mfc.capacity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.ortools.Loader;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 OR-Tools 경로(네이티브). 네이티브를 올릴 수 없는 PC(2026-11-09 개발 PC — Windows 애플리케이션 제어 정책)에서는 건너뛴다 —
 * 리눅스(LAN·Docker·CI)에서 돈다.
 */
class OrToolsRouterTest {

  private static boolean nativeOk;

  @BeforeAll
  static void loadNative() {
    try {
      Loader.loadNativeLibraries();
      nativeOk = true;
    } catch (Throwable e) {
      nativeOk = false;
    }
  }

  @Test
  void findsShortestOpenPathFromStart() {
    assumeTrue(nativeOk, "OR-Tools 네이티브를 이 PC 에서 올릴 수 없음");
    // 1차원 위치: 출발 0, 칸 9·1·5·3 → 최단 열린 경로 1 → 3 → 5 → 9 (9 m)
    double[] x = {0, 9, 1, 5, 3};
    double[][] m = new double[5][5];
    for (int i = 0; i < 5; i++) {
      for (int j = 0; j < 5; j++) {
        m[i][j] = Math.abs(x[i] - x[j]);
      }
    }
    List<Integer> path = new OrToolsRouter().shortestOpenPath(m, 0, List.of(1, 2, 3, 4), 500);
    assertThat(path).containsExactly(2, 4, 3, 1);
  }
}
