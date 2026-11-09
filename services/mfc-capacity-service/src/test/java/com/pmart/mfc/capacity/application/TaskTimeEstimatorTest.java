package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pmart.mfc.capacity.infrastructure.FulfillmentClient;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView;
import com.pmart.mfc.capacity.infrastructure.LocationClient;
import com.pmart.mfc.capacity.infrastructure.LocationClient.Attr;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 배정 추천 작업 시간 추정(0.11.0) — PICK·PACK 은 유형 분을 평균으로 비율, 이동은 걷는 시간 + 처리 초. */
class TaskTimeEstimatorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static PlanProperties props() {
    return new PlanProperties(
        null,
        Map.of("PICK", 7, "PACK", 4, "MOVE", 5),
        null,
        null,
        null,
        null,
        null,
        "http://loc",
        "CRT-A-01",
        new PlanProperties.Travel(1.0, 10, 5, "PAK-A-01"));
  }

  /** 1차원 좌표 x 로 거리표(|Δx|). 모두 상온 C 그룹 선반, 출발·포장은 선반 아님. */
  private static LocationClient.Table table(Map<String, Double> xs) {
    List<String> codes = List.copyOf(xs.keySet());
    double[][] m = new double[codes.size()][codes.size()];
    Map<String, Attr> attrs = new LinkedHashMap<>();
    for (int i = 0; i < codes.size(); i++) {
      String c = codes.get(i);
      attrs.put(
          c,
          c.startsWith("A-")
              ? new Attr("STORAGE", "AMBIENT", "C1")
              : new Attr("CART", "AMBIENT", null));
      for (int j = 0; j < codes.size(); j++) {
        m[i][j] = Math.abs(xs.get(c) - xs.get(codes.get(j)));
      }
    }
    return new LocationClient.Table(codes, m, List.of(), attrs);
  }

  private String detail(String code, int qty) {
    return "{\"lines\":[{\"picks\":[{\"remainingQty\":"
        + qty
        + ",\"locations\":[{\"locationCode\":\""
        + code
        + "\",\"qty\":9,\"allocatedQty\":0}]}]}]}";
  }

  @Test
  void pickMinutesScaleAroundDocumentAverageAndMovesAreWalkPlusHandling() {
    FulfillmentClient ful = mock(FulfillmentClient.class);
    when(ful.taskDetail("NEAR")).thenReturn(mapper.readTree(detail("A-C1-01-1-01", 1)));
    when(ful.taskDetail("FAR")).thenReturn(mapper.readTree(detail("A-C1-09-1-01", 1)));
    LocationClient loc = mock(LocationClient.class);
    Map<String, Double> xs = new LinkedHashMap<>();
    xs.put("CRT-A-01", 0.0);
    xs.put("PAK-A-01", 0.0);
    xs.put("A-C1-01-1-01", 10.0);
    xs.put("A-C1-09-1-01", 50.0);
    when(loc.distances(eq("M"), any())).thenReturn(table(xs));
    var est =
        new TaskTimeEstimator(ful, loc, props())
            .estimate(
                "M",
                List.of(
                    new TaskView("NEAR", "PICK", "READY", 1, null, null),
                    new TaskView("FAR", "PICK", "READY", 1, null, null),
                    new TaskView("MV", "MOVE", "READY", 0, null, null)),
                Map.of(
                    "MV",
                    new MoveView(
                        "MV",
                        "MOVE",
                        "ACTIVE",
                        false,
                        0,
                        null,
                        null,
                        "A-C1-01-1-01",
                        "A-C1-09-1-01",
                        2)),
                Map.of("PICK", 7, "PACK", 4, "MOVE", 5));
    // NEAR: 걷기 20 m(왕복) + 처리 15 초 = 35 초, FAR: 100 + 15 = 115 초, 평균 75 초 → 7분 × 35/75 ≈ 3, 7 ×
    // 115/75 ≈ 11
    assertThat(est.minutes().get("NEAR")).isEqualTo(4); // 35/75 = 0.47 → 하한 0.5 → 3.5 → 4
    assertThat(est.minutes().get("FAR")).isEqualTo(11);
    // MOVE: 출발 0 → 10 → 50 = 50 m + 처리(1줄 10 + 2개 × 5) 20 초 = 70 초 → 2분(올림)
    assertThat(est.minutes().get("MV")).isEqualTo(2);
    assertThat(est.partial()).isFalse();
    assertThat(est.settings()).containsEntry("walkSpeedMps", 1.0);
  }

  @Test
  void unreadableTaskKeepsTypeMinutesAndMarksPartial() {
    FulfillmentClient ful = mock(FulfillmentClient.class);
    when(ful.taskDetail("X")).thenThrow(new IllegalStateException("down"));
    var est =
        new TaskTimeEstimator(ful, mock(LocationClient.class), props())
            .estimate(
                "M",
                List.of(new TaskView("X", "PICK", "READY", 1, null, null)),
                Map.of(),
                Map.of("PICK", 7));
    assertThat(est.minutes()).doesNotContainKey("X");
    assertThat(est.partial()).isTrue();
  }

  @Test
  void enabledOnlyWithAllTravelSettings() {
    FulfillmentClient ful = mock(FulfillmentClient.class);
    LocationClient loc = mock(LocationClient.class);
    assertThat(new TaskTimeEstimator(ful, loc, props()).enabled()).isTrue();
    var noTravel =
        new PlanProperties(
            null, null, null, null, null, null, null, "http://loc", "CRT-A-01", null);
    assertThat(new TaskTimeEstimator(ful, loc, noTravel).enabled()).isFalse();
  }
}
