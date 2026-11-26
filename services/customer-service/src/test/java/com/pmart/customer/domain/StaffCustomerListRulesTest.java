package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StaffCustomerListRulesTest {

  @Test
  void nameNeedsTwoToFiftyCharactersAndEscapesWildcards() {
    assertThat(StaffCustomerListRules.name("  김철 ")).isEqualTo("김철");
    assertThat(StaffCustomerListRules.name("   ")).isNull();
    assertThat(StaffCustomerListRules.nameProblem("김")).isNotNull();
    assertThat(StaffCustomerListRules.nameProblem("김철")).isNull();
    assertThat(StaffCustomerListRules.nameProblem("가".repeat(51))).isNotNull();
    assertThat(StaffCustomerListRules.prefixPattern("a%b_c\\")).isEqualTo("a\\%b\\_c\\\\%");
  }

  @Test
  void joinedRangeIsSeoulDatesInclusive() {
    var r = StaffCustomerListRules.range(LocalDate.of(2015, 3, 1), LocalDate.of(2015, 3, 31));
    assertThat(r.from()).isEqualTo(Instant.parse("2015-02-28T15:00:00Z"));
    assertThat(r.toExclusive()).isEqualTo(Instant.parse("2015-03-31T15:00:00Z"));
    assertThat(
            StaffCustomerListRules.rangeProblem(LocalDate.of(2015, 4, 1), LocalDate.of(2015, 3, 1)))
        .isNotNull();
  }

  @Test
  void cursorRoundTrip() {
    var c =
        new StaffCustomerListRules.Cursor(Instant.parse("2015-03-10T03:00:00.123456Z"), "CU-1:x");
    assertThat(StaffCustomerListRules.decode(StaffCustomerListRules.encode(c))).isEqualTo(c);
    assertThat(StaffCustomerListRules.decode("@@bad")).isNull();
    assertThat(StaffCustomerListRules.sizeProblem(201)).isNotNull();
  }
}
