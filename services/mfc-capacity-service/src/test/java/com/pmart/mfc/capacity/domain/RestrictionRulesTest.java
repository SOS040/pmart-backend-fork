package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.domain.CapacityRules.HourWindow;
import com.pmart.mfc.capacity.domain.RestrictionRules.Active;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestrictionRulesTest {

  static final Instant T10 = Instant.parse("2026-11-08T01:00:00Z");
  static final HourWindow W10 = new HourWindow(T10, T10.plusSeconds(3600));
  static final HourWindow W11 = new HourWindow(T10.plusSeconds(3600), T10.plusSeconds(7200));

  @Test
  void restrictionsApplyToOverlappingWindowsOnlyAndStack() {
    Active stop = new Active("S", "INTAKE_STOP", T10.plusSeconds(3600), null, null);
    Active cut = new Active("C", "CAPACITY_REDUCE", T10, T10.plusSeconds(3600), 2);
    Active cut2 = new Active("C2", "CAPACITY_REDUCE", T10.plusSeconds(1800), null, 1);
    List<Active> all = List.of(stop, cut, cut2);
    assertThat(RestrictionRules.intakeStop(all, W10)).isNull();
    assertThat(RestrictionRules.intakeStop(all, W11)).isEqualTo("S");
    // 겹치는 축소 중 가장 작은 값, 범위 밖은 설정값
    assertThat(RestrictionRules.capacity(30, all, W10)).isEqualTo(1);
    assertThat(RestrictionRules.capacity(30, List.of(cut), W11)).isEqualTo(30);
    assertThat(RestrictionRules.applying(all, W10)).containsExactly("C", "C2");
  }

  @Test
  void requestValidation() {
    assertThat(RestrictionRules.invalid("INTAKE_STOP", T10, null, null)).isNull();
    assertThat(RestrictionRules.invalid("CAPACITY_REDUCE", T10, null, null)).isNotNull();
    assertThat(RestrictionRules.invalid("CAPACITY_REDUCE", T10, T10, 1)).isNotNull();
    assertThat(RestrictionRules.invalid("INTAKE_STOP", T10, null, 3)).isNotNull();
    assertThat(RestrictionRules.invalid("WORK_STOP", T10, null, null)).isNotNull();
  }
}
