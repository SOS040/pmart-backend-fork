package com.pmart.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.pricing.domain.PriceRules.LineProblem;
import com.pmart.pricing.domain.PriceVersion.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceRulesTest {

  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  static PriceVersion v(int version, long price, Instant from, State state) {
    return new PriceVersion("E-00000001", version, price, null, from, null, state, "AP-" + version);
  }

  @Test
  void lineChecksFormatExistenceAndZeroPrice() {
    assertThat(PriceRules.checkLine("X-1", 1000L, true)).contains(LineProblem.SKU_INVALID);
    assertThat(PriceRules.checkLine("E-00000001", 1000L, false))
        .contains(LineProblem.SKU_NOT_FOUND);
    assertThat(PriceRules.checkLine("E-00000001", null, true)).contains(LineProblem.PRICE_MISSING);
    assertThat(PriceRules.checkLine("E-00000001", 0L, true))
        .contains(LineProblem.ZERO_PRICE_UNDECIDED);
    assertThat(PriceRules.checkLine("B-00000002", 4500L, true)).isEmpty();
  }

  @Test
  void pastRequestedEffectiveTimeIsNotRetroactive() {
    Instant applied = T0.plusSeconds(60);
    assertThat(PriceRules.effectiveFrom(T0, applied)).isEqualTo(applied);
    assertThat(PriceRules.effectiveFrom(null, applied)).isEqualTo(applied);
    assertThat(PriceRules.effectiveFrom(T0.plusSeconds(3600), applied))
        .isEqualTo(T0.plusSeconds(3600));
  }

  @Test
  void newVersionAtOrBeforeScheduledVersionConflicts() {
    List<PriceVersion> existing =
        List.of(v(1, 1000, T0, State.EFFECTIVE), v(2, 1200, T0.plusSeconds(3600), State.SCHEDULED));
    assertThat(PriceRules.scheduleConflict(existing, T0.plusSeconds(1800))).isTrue();
    assertThat(PriceRules.scheduleConflict(existing, T0.plusSeconds(3600))).isTrue();
    assertThat(PriceRules.scheduleConflict(existing, T0.plusSeconds(7200))).isFalse();
    // 이미 발효된 버전은 겹침 판정 대상이 아니다(새 버전이 뒤에 온다)
    assertThat(PriceRules.scheduleConflict(List.of(v(1, 1000, T0, State.EFFECTIVE)), T0)).isFalse();
  }

  @Test
  void effectivePriceAtTimeAndNextChange() {
    List<PriceVersion> versions =
        List.of(
            v(1, 1000, T0, State.SUPERSEDED),
            v(2, 1200, T0.plusSeconds(100), State.EFFECTIVE),
            v(3, 1300, T0.plusSeconds(200), State.SCHEDULED));
    assertThat(PriceRules.effectiveAt(versions, T0.minusSeconds(1))).isEmpty();
    assertThat(PriceRules.effectiveAt(versions, T0.plusSeconds(50)).orElseThrow().version())
        .isEqualTo(1);
    assertThat(PriceRules.effectiveAt(versions, T0.plusSeconds(150)).orElseThrow().version())
        .isEqualTo(2);
    assertThat(PriceRules.nextAfter(versions, T0.plusSeconds(150)).orElseThrow().version())
        .isEqualTo(3);
    assertThat(PriceRules.nextAfter(versions, T0.plusSeconds(250))).isEmpty();
  }

  @Test
  void lineAmountOverflowIsAnError() {
    assertThat(PriceRules.lineAmount(4500, 3)).isEqualTo(13500);
    assertThatThrownBy(() -> PriceRules.lineAmount(Long.MAX_VALUE, 2))
        .isInstanceOf(ArithmeticException.class);
  }
}
