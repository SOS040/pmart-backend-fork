package com.pmart.mfc.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 인계 허가 유효시간: 센터 값 → default → 없음(D-2, 2026-11-08). */
class FulfillmentPropertiesTest {

  @Test
  void fenceTtlFallsBackToDefault() {
    var p =
        new FulfillmentProperties(
            null,
            null,
            null,
            Map.of("MFC-A", Duration.ofMinutes(2), "default", Duration.ofMinutes(3)),
            null);
    assertThat(p.handoverFenceTtl("MFC-A")).isEqualTo(Duration.ofMinutes(2));
    assertThat(p.handoverFenceTtl("MFC-B")).isEqualTo(Duration.ofMinutes(3));
    assertThat(new FulfillmentProperties(null, null, null, null, null).handoverFenceTtl("MFC-B"))
        .isNull();
  }
}
