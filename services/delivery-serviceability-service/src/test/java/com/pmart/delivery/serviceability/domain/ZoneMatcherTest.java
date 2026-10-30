package com.pmart.delivery.serviceability.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.delivery.serviceability.domain.ZoneMatcher.Zone;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneMatcherTest {

  /** 경도 x0~x1, 위도 y0~y1 사각형(닫힌 링). */
  static List<double[]> box(double x0, double y0, double x1, double y1) {
    return List.of(
        new double[] {x0, y0},
        new double[] {x1, y0},
        new double[] {x1, y1},
        new double[] {x0, y1},
        new double[] {x0, y0});
  }

  static Zone zone(String id, String mfc, Integer priority, List<List<double[]>> polygon) {
    return new Zone(id, mfc, priority, List.of(polygon));
  }

  // 서울 서초 부근 가상 권역
  static final Zone A = zone("Z-A", "MFC-A", null, List.of(box(127.00, 37.48, 127.04, 37.52)));

  @Test
  void insideOutside() {
    assertThat(ZoneMatcher.match(List.of(A), 37.50, 127.02).result()).isEqualTo("SERVICEABLE");
    assertThat(ZoneMatcher.match(List.of(A), 37.50, 127.02).zone().mfcId()).isEqualTo("MFC-A");
    assertThat(ZoneMatcher.match(List.of(A), 37.60, 127.02).result()).isEqualTo("OUT_OF_AREA");
  }

  @Test
  void holeIsOutside() {
    Zone withHole =
        zone(
            "Z-H",
            "MFC-A",
            null,
            List.of(box(127.00, 37.48, 127.04, 37.52), box(127.01, 37.49, 127.02, 37.50)));
    assertThat(ZoneMatcher.match(List.of(withHole), 37.495, 127.015).result())
        .isEqualTo("OUT_OF_AREA");
    assertThat(ZoneMatcher.match(List.of(withHole), 37.51, 127.03).result())
        .isEqualTo("SERVICEABLE");
  }

  @Test
  void overlapNeedsUniqueHighestPriority() {
    Zone b = zone("Z-B", "MFC-B", null, List.of(box(127.02, 37.48, 127.06, 37.52)));
    // 겹치는 곳, 우선순위 없음 → OVERLAP(임의 선택 금지)
    var m = ZoneMatcher.match(List.of(A, b), 37.50, 127.03);
    assertThat(m.result()).isEqualTo("OVERLAP");
    assertThat(m.overlapping()).hasSize(2);
    // 우선순위가 높은 쪽이 하나면 그 권역
    Zone aHigh = new Zone("Z-A", "MFC-A", 10, A.polygons());
    Zone bLow = new Zone("Z-B", "MFC-B", 1, b.polygons());
    assertThat(ZoneMatcher.match(List.of(aHigh, bLow), 37.50, 127.03).zone().mfcId())
        .isEqualTo("MFC-A");
    // 같은 우선순위면 OVERLAP
    Zone bSame = new Zone("Z-B", "MFC-B", 10, b.polygons());
    assertThat(ZoneMatcher.match(List.of(aHigh, bSame), 37.50, 127.03).result())
        .isEqualTo("OVERLAP");
  }

  @Test
  void validateRings() {
    assertThat(ZoneMatcher.validate(A.polygons())).isEmpty();
    assertThat(
            ZoneMatcher.validate(
                List.of(
                    List.of(
                        List.of(
                            new double[] {127, 37},
                            new double[] {128, 37},
                            new double[] {128, 38})))))
        .isPresent();
    assertThat(
            ZoneMatcher.validate(
                List.of(
                    List.of(
                        List.of(
                            new double[] {127, 37},
                            new double[] {128, 37},
                            new double[] {128, 38},
                            new double[] {127, 38})))))
        .get()
        .asString()
        .contains("닫혀");
    // 위도·경도를 뒤집어 넣은 경우(위도 127)
    assertThat(ZoneMatcher.validate(List.of(List.of(box(37.0, 127.0, 37.1, 127.1))))).isPresent();
  }
}
