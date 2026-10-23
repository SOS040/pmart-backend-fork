package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PolicyValueTest {

  @Test
  void requireReturnsValueWhenSet() {
    PolicyValue<Integer> v = PolicyValue.of(10, 3, Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(v.require("purchase.limit")).isEqualTo(10);
  }

  @Test
  void notSetIsNotZero() {
    PolicyValue<Integer> v = PolicyValue.notSet();
    assertThatThrownBy(() -> v.require("slot.capacity"))
        .isInstanceOf(PmartException.class)
        .satisfies(e -> assertThat(((PmartException) e).code()).isEqualTo("CONFIG_REQUIRED"));
  }

  @Test
  void unavailableAlsoBlocks() {
    assertThatThrownBy(() -> PolicyValue.<Integer>unavailable().require("x"))
        .isInstanceOf(PmartException.class);
  }
}
