package com.pmart.mfc.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DistancesTest {

  @Test
  void manhattanMetersRoundedToTenthAndSymmetric() {
    double[][] m = Distances.manhattan(List.of(0.0, 3.0, 25.88), List.of(0.0, 4.0, 11.65));
    assertThat(m[0][0]).isZero();
    assertThat(m[0][1]).isEqualTo(7.0);
    assertThat(m[1][0]).isEqualTo(7.0);
    assertThat(m[0][2]).isEqualTo(37.5); // 25.88 + 11.65 = 37.53
    assertThat(m[1][2]).isEqualTo(30.5); // 22.88 + 7.65 = 30.53
  }
}
