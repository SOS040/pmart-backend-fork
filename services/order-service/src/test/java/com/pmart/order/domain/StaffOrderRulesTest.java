package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaffOrderRulesTest {

  static final Instant T = Instant.parse("2026-11-15T00:00:00Z");

  @Test
  void onlyStaffRolesAllowed() {
    assertThat(StaffOrderRules.allowed(List.of("CS_AGENT"))).isTrue();
    assertThat(StaffOrderRules.allowed(List.of("MFC_SUPERVISOR", "RIDER_OPS"))).isTrue();
    assertThat(StaffOrderRules.allowed(List.of("OPERATOR_ADMIN"))).isTrue();
    assertThat(StaffOrderRules.allowed(List.of("MFC_SUPERVISOR"))).isFalse();
    assertThat(StaffOrderRules.allowed(List.of())).isFalse();
  }

  @Test
  void rangeRequiredWithoutKeyAndCappedAt31Days() {
    assertThat(StaffOrderRules.rangeProblem(false, null, null)).isNotNull();
    assertThat(StaffOrderRules.rangeProblem(false, T, null)).isNotNull();
    assertThat(StaffOrderRules.rangeProblem(false, T, T.plus(Duration.ofDays(31)))).isNull();
    assertThat(StaffOrderRules.rangeProblem(false, T, T.plus(Duration.ofDays(31)).plusSeconds(1)))
        .isNotNull();
    assertThat(StaffOrderRules.rangeProblem(true, null, null)).isNull();
    assertThat(StaffOrderRules.rangeProblem(true, T, T.plus(Duration.ofDays(400)))).isNull();
    assertThat(StaffOrderRules.rangeProblem(true, T, T)).isNotNull(); // 순서 오류는 늘 거절
  }

  @Test
  void phoneIsNormalizedToDigitsForExactMatch() {
    assertThat(StaffOrderRules.normalizePhone("010-1234-5678")).isEqualTo("01012345678");
    assertThat(StaffOrderRules.normalizePhone(" 02 123 4567 ")).isEqualTo("021234567");
    assertThat(StaffOrderRules.normalizePhone("5678")).isNull(); // 부분 검색 불가
    assertThat(StaffOrderRules.normalizePhone("010123456789")).isNull();
  }

  @Test
  void phoneMaskedKeepingHead3AndTail4() {
    assertThat(StaffOrderRules.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
    assertThat(StaffOrderRules.maskPhone("01012345678")).isEqualTo("010-****-5678");
    assertThat(StaffOrderRules.maskPhone("0311234567")).isEqualTo("031-***-4567");
    assertThat(StaffOrderRules.maskPhone("1234")).isEqualTo("****");
    assertThat(StaffOrderRules.maskPhone(null)).isNull();
  }
}
