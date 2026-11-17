package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 고객 주문 내역 규칙(2026-11-18, 계약 0.12.0). */
class OrderHistoryRulesTest {

  static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

  @Test
  void defaultPeriodIsLastThreeMonthsFromSeoulMidnight() {
    var p = OrderHistoryRules.period(null, null, TODAY);
    // 2026-08-18 00:00 Asia/Seoul = 2026-08-17T15:00Z, 끝은 열림(지금까지)
    assertThat(p.from()).isEqualTo(Instant.parse("2026-08-17T15:00:00Z"));
    assertThat(p.toExclusive()).isNull();
  }

  @Test
  void explicitPeriodIncludesBothDays() {
    var p = OrderHistoryRules.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), TODAY);
    assertThat(p.from()).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"));
    assertThat(p.toExclusive()).isEqualTo(Instant.parse("2026-01-31T15:00:00Z"));
    // 같은 날도 된다(그 하루)
    var day = OrderHistoryRules.period(TODAY, TODAY, TODAY);
    assertThat(day.from()).isEqualTo(Instant.parse("2026-11-17T15:00:00Z"));
    assertThat(day.toExclusive()).isEqualTo(Instant.parse("2026-11-18T15:00:00Z"));
  }

  @Test
  void oneSidedPeriodCutsOnlyThatSide() {
    var fromOnly = OrderHistoryRules.period(LocalDate.of(2025, 1, 1), null, TODAY);
    assertThat(fromOnly.from()).isEqualTo(Instant.parse("2024-12-31T15:00:00Z"));
    assertThat(fromOnly.toExclusive()).isNull();
    var toOnly = OrderHistoryRules.period(null, LocalDate.of(2025, 1, 1), TODAY);
    assertThat(toOnly.from()).isNull();
    assertThat(toOnly.toExclusive()).isEqualTo(Instant.parse("2025-01-01T15:00:00Z"));
  }

  @Test
  void fromAfterToIsRejected() {
    assertThatThrownBy(
            () ->
                OrderHistoryRules.period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), TODAY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void queryIsTrimmedLowercasedAndBlankIgnored() {
    assertThat(OrderHistoryRules.query(null)).isNull();
    assertThat(OrderHistoryRules.query("   ")).isNull();
    assertThat(OrderHistoryRules.query("  Apple 사과 ")).isEqualTo("apple 사과");
    assertThatThrownBy(() -> OrderHistoryRules.query("가".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void likePatternEscapesWildcards() {
    assertThat(OrderHistoryRules.likePattern("두부")).isEqualTo("%두부%");
    assertThat(OrderHistoryRules.likePattern("100%_a\\b")).isEqualTo("%100\\%\\_a\\\\b%");
  }

  @Test
  void onlyFinishedOrdersCanBeHidden() {
    assertThat(OrderHistoryRules.hideable("COMPLETED")).isTrue();
    assertThat(OrderHistoryRules.hideable("CANCELLED")).isTrue();
    assertThat(OrderHistoryRules.hideable("EXPIRED")).isTrue();
    for (String s :
        new String[] {
          "CONFIRMED", "PREPARING", "PARTIALLY_HANDED_OVER", "IN_DELIVERY", "PARTIALLY_DELIVERED"
        }) {
      assertThat(OrderHistoryRules.hideable(s)).as(s).isFalse();
    }
  }
}
