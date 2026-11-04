package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationScopeTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void setsAndRestoresPreviousValue() {
    MDC.put("correlationId", "REQ-1");
    try (CorrelationScope c = CorrelationScope.open(null, "SAGA-1")) {
      assertThat(MDC.get("correlationId")).isEqualTo("SAGA-1");
      try (CorrelationScope inner = CorrelationScope.open(null, "CLM-9")) {
        assertThat(MDC.get("correlationId")).isEqualTo("CLM-9");
      }
      assertThat(MDC.get("correlationId")).isEqualTo("SAGA-1");
    }
    assertThat(MDC.get("correlationId")).isEqualTo("REQ-1");
  }

  @Test
  void removesWhenNothingWasSetBefore() {
    try (CorrelationScope c = CorrelationScope.open(null, "SAGA-1")) {
      assertThat(MDC.get("correlationId")).isEqualTo("SAGA-1");
    }
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  void blankLeavesCurrentValue() {
    MDC.put("correlationId", "REQ-1");
    try (CorrelationScope c = CorrelationScope.open(null, " ")) {
      assertThat(MDC.get("correlationId")).isEqualTo("REQ-1");
    }
    assertThat(MDC.get("correlationId")).isEqualTo("REQ-1");
  }
}
