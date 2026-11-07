package com.pmart.mfc.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 위치 코드 문법(Q-03 결정 2026-11-07, ADR-0042). */
class LocationCodesTest {

  @Test
  void shelfCodesFollowTemperatureAreaRackLevelSlot() {
    var ok = LocationCodes.check("C-S1-03-2-04", "STORAGE", "CHILLED", null);
    assertThat(ok.problem()).isEmpty();
    assertThat(ok.areaCode()).isEqualTo("S1");
    assertThat(LocationCodes.check("F-P2-10-9-99", "PICKING", "FROZEN", "P2").problem()).isEmpty();
  }

  @Test
  void shelfCodeMismatchesAreRejected() {
    // 옛 자유 형식, 온도대 불일치, 구역 불일치, 단 0
    assertThat(LocationCodes.check("C-01", "STORAGE", "CHILLED", null).problem()).isPresent();
    assertThat(LocationCodes.check("A-S1-03-2-04", "STORAGE", "CHILLED", null).problem())
        .hasValueSatisfying(p -> assertThat(p).contains("온도대"));
    assertThat(LocationCodes.check("C-S1-03-2-04", "STORAGE", "CHILLED", "S2").problem())
        .hasValueSatisfying(p -> assertThat(p).contains("areaCode"));
    assertThat(LocationCodes.check("C-S1-03-0-04", "STORAGE", "CHILLED", null).problem())
        .isPresent();
  }

  @Test
  void otherZonesUsePrefixTemperatureNumber() {
    assertThat(LocationCodes.check("RCV-C-01", "RECEIVING", "CHILLED", null).problem()).isEmpty();
    assertThat(LocationCodes.check("QRT-F-02", "QUARANTINE", "FROZEN", "Q").areaCode())
        .isEqualTo("Q");
    assertThat(LocationCodes.check("RCV-A-01", "RECEIVING", "CHILLED", null).problem()).isPresent();
    assertThat(LocationCodes.check("PAK-A-01", "RECEIVING", "AMBIENT", null).problem()).isPresent();
    assertThat(LocationCodes.check("DSP-A-1", "DISPATCH_STAGING", "AMBIENT", null).problem())
        .isPresent();
  }
}
