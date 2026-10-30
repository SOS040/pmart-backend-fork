package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ServiceabilityTest {

  static final Instant AT = Instant.parse("2026-10-30T01:00:00Z");

  @Test
  void mapsCheckResultToCustomerState() {
    var ok = Serviceability.fromCheck("SERVICEABLE", "MFC-1", "Z-1", 3, "SC-1", AT);
    assertThat(ok.state()).isEqualTo("SERVICEABLE");
    assertThat(ok.serviceable()).isTrue();
    assertThat(ok.mfcId()).isEqualTo("MFC-1");

    var out = Serviceability.fromCheck("OUT_OF_AREA", null, null, 3, "SC-2", AT);
    assertThat(out.state()).isEqualTo("OUT_OF_SERVICE_AREA");
    assertThat(out.serviceable()).isFalse();
  }

  @Test
  void overlapAndMissingInputAreUnknownWithoutMfc() {
    // 중첩·입력 보완은 임의 MFC 배정 금지
    var overlap = Serviceability.fromCheck("OVERLAP", "MFC-1", "Z-1", 3, "SC-3", AT);
    assertThat(overlap.state()).isEqualTo("UNKNOWN");
    assertThat(overlap.mfcId()).isNull();
    assertThat(overlap.result()).isEqualTo("OVERLAP");
    assertThat(Serviceability.fromCheck("NEEDS_INPUT", null, null, null, "SC-4", AT).state())
        .isEqualTo("UNKNOWN");
    // SERVICEABLE 인데 MFC 가 없으면 확정하지 않는다
    assertThat(Serviceability.fromCheck("SERVICEABLE", null, null, 3, "SC-5", AT).state())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void unknownWhenNotJudged() {
    var u = Serviceability.unknown("DEPENDENCY_UNAVAILABLE", AT);
    assertThat(u.state()).isEqualTo("UNKNOWN");
    assertThat(u.checkId()).isNull();
  }

  @Test
  void addressRules() {
    assertThat(AddressRules.limitReached(20, 19)).isFalse();
    assertThat(AddressRules.limitReached(20, 20)).isTrue();
    assertThat(AddressRules.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
    assertThat(AddressRules.maskPhone("0212345678")).isEqualTo("021-***-5678");
    assertThat(AddressRules.maskPhone("123")).isEqualTo("***");
  }
}
