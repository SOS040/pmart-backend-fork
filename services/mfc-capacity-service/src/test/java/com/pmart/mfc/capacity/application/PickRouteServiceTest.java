package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.PickRouteRules;
import com.pmart.mfc.capacity.infrastructure.LocationClient;
import com.pmart.mfc.capacity.infrastructure.LocationClient.Attr;
import com.pmart.mfc.capacity.infrastructure.OrToolsRouter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 피킹 방문 순서(ADR-0050 B2) — 냉장 → 냉동 → 상온 C~H, 그룹 안 최단, 출발 = 장바구니 보관 자리, 선반 아닌 칸·좌표 없는 칸은 맨 뒤. */
class PickRouteServiceTest {

  private static PlanProperties props(String start) {
    return new PlanProperties(null, null, null, null, null, null, null, "http://loc", start);
  }

  /** 좌표: 1차원 x 만(직각 거리 = |Δx|). */
  private static LocationClient.Table table(Map<String, Object[]> spec, List<String> missing) {
    List<String> codes = List.copyOf(spec.keySet());
    double[][] m = new double[codes.size()][codes.size()];
    Map<String, Attr> attrs = new LinkedHashMap<>();
    for (int i = 0; i < codes.size(); i++) {
      Object[] a = spec.get(codes.get(i));
      attrs.put(codes.get(i), new Attr((String) a[0], (String) a[1], (String) a[2]));
      for (int j = 0; j < codes.size(); j++) {
        m[i][j] = Math.abs((double) spec.get(codes.get(i))[3] - (double) spec.get(codes.get(j))[3]);
      }
    }
    return new LocationClient.Table(codes, m, missing, attrs);
  }

  @Test
  void groupsChilledThenFrozenThenAmbientByAreaAndNonShelfLast() {
    Map<String, Object[]> spec = new LinkedHashMap<>();
    spec.put("CRT-A-01", new Object[] {"CART", "AMBIENT", null, 30.0});
    spec.put("A-D1-01-1-01", new Object[] {"STORAGE", "AMBIENT", "D1", 26.0});
    spec.put("A-C1-02-1-01", new Object[] {"STORAGE", "AMBIENT", "C1", 29.0});
    spec.put("F-A1-01-1-01", new Object[] {"STORAGE", "FROZEN", "A1", 5.0});
    spec.put("C-B1-01-1-01", new Object[] {"STORAGE", "CHILLED", "B1", 15.0});
    spec.put("C-B2-03-1-01", new Object[] {"STORAGE", "CHILLED", "B2", 20.0});
    spec.put("PAK-A-01", new Object[] {"PACKING", "AMBIENT", null, 30.0});
    LocationClient loc = mock(LocationClient.class);
    when(loc.distances(eq("M"), any())).thenReturn(table(spec, List.of("A-H1-01-1-01")));
    OrToolsRouter router = mock(OrToolsRouter.class); // 엔진 없음 → 가까운 칸부터
    when(router.shortestOpenPath(any(), anyInt(), any(), anyLong())).thenReturn(null);
    var r =
        new PickRouteService(loc, router, props("CRT-A-01"))
            .route(
                "M",
                List.of(
                    "A-D1-01-1-01",
                    "PAK-A-01",
                    "C-B1-01-1-01",
                    "A-C1-02-1-01",
                    "F-A1-01-1-01",
                    "C-B2-03-1-01",
                    "A-H1-01-1-01"));
    // 냉장 둘(출발 30 에서 가까운 20 → 15) → 냉동 → 상온 C → D → 그 밖(코드 순)
    assertThat(r.ordered())
        .containsExactly(
            "C-B2-03-1-01",
            "C-B1-01-1-01",
            "F-A1-01-1-01",
            "A-C1-02-1-01",
            "A-D1-01-1-01",
            "A-H1-01-1-01",
            "PAK-A-01");
    assertThat(r.method()).isEqualTo("NEAREST_FALLBACK");
    assertThat(r.startLocation()).isEqualTo("CRT-A-01");
    assertThat(r.totalMeters()).isEqualTo(10 + 5 + 10 + 24 + 3); // 30→20→15→5→29→26
    assertThat(r.missing()).containsExactly("A-H1-01-1-01");
    assertThat(r.limitations()).contains("WALLS_NOT_CONSIDERED", "NON_SHELF_OR_NO_POSITION_LAST");
  }

  @Test
  void usesEnginePathWhenAvailable() {
    Map<String, Object[]> spec = new LinkedHashMap<>();
    spec.put("CRT-A-01", new Object[] {"CART", "AMBIENT", null, 0.0});
    spec.put("A-C1-01-1-01", new Object[] {"STORAGE", "AMBIENT", "C1", 1.0});
    spec.put("A-C1-01-1-02", new Object[] {"STORAGE", "AMBIENT", "C1", 2.0});
    LocationClient loc = mock(LocationClient.class);
    when(loc.distances(eq("M"), any())).thenReturn(table(spec, List.of()));
    OrToolsRouter router = mock(OrToolsRouter.class);
    when(router.shortestOpenPath(any(), eq(0), any(), anyLong())).thenReturn(List.of(1, 2));
    var r =
        new PickRouteService(loc, router, props("CRT-A-01"))
            .route("M", List.of("A-C1-01-1-02", "A-C1-01-1-01"));
    assertThat(r.method()).isEqualTo("ROUTING");
    assertThat(r.ordered()).containsExactly("A-C1-01-1-01", "A-C1-01-1-02");
  }

  @Test
  void startLocationMustBeConfigured() {
    var svc =
        new PickRouteService(mock(LocationClient.class), mock(OrToolsRouter.class), props(null));
    assertThatThrownBy(() -> svc.route("M", List.of("A-C1-01-1-01")))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining(PickRouteService.START_KEY);
  }

  @Test
  void groupRankFollowsDocumentOrder() {
    assertThat(PickRouteRules.groupRank("STORAGE", "CHILLED", "B3")).isZero();
    assertThat(PickRouteRules.groupRank("STORAGE", "FROZEN", "A1")).isEqualTo(1);
    assertThat(PickRouteRules.groupRank("STORAGE", "AMBIENT", "C1")).isEqualTo(2);
    assertThat(PickRouteRules.groupRank("STORAGE", "AMBIENT", "H3")).isEqualTo(7);
    assertThat(PickRouteRules.groupRank("STORAGE", "AMBIENT", "R1")).isEqualTo(9);
    assertThat(PickRouteRules.groupRank("PACKING", "AMBIENT", null)).isEqualTo(100);
  }
}
