package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderNumberRulesTest {

  @Test
  void eightDigitsWithoutLeadingZero() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 2_000; i++) {
      String n = OrderNumberRules.next();
      assertThat(n).matches("[1-9][0-9]{7}");
      seen.add(n);
    }
    assertThat(seen.size()).isGreaterThan(1_990);
  }

  @Test
  void normalizeAcceptsSpacesAndHyphens() {
    assertThat(OrderNumberRules.normalize("1234-5678")).isEqualTo("12345678");
    assertThat(OrderNumberRules.normalize(" 12345678 ")).isEqualTo("12345678");
    assertThat(OrderNumberRules.normalize("1234567")).isNull();
    assertThat(OrderNumberRules.normalize("1234567a")).isNull();
    assertThat(OrderNumberRules.normalize(null)).isNull();
  }
}
