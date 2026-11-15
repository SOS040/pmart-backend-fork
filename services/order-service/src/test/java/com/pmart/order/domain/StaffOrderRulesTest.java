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

  @Test
  void nameMaskedKeepingFirstAndLastChar() {
    assertThat(StaffOrderRules.maskName("홍길동")).isEqualTo("홍*동");
    assertThat(StaffOrderRules.maskName("홍길")).isEqualTo("홍*");
    assertThat(StaffOrderRules.maskName("남궁민수")).isEqualTo("남**수");
    assertThat(StaffOrderRules.maskName("홍")).isEqualTo("*");
    assertThat(StaffOrderRules.maskName(" Kim ")).isEqualTo("K*m");
  }

  @Test
  void reasonMustBe2To200CharsAfterStrip() {
    assertThat(StaffOrderRules.reasonProblem(null)).isNotNull();
    assertThat(StaffOrderRules.reasonProblem("  가 ")).isNotNull();
    assertThat(StaffOrderRules.reasonProblem("문의")).isNull();
    assertThat(StaffOrderRules.reasonProblem("가".repeat(200))).isNull();
    assertThat(StaffOrderRules.reasonProblem("가".repeat(201))).isNotNull();
  }

  @Test
  void shippingMaskedExceptRevealedFields() {
    var s =
        new ShippingAddress(
            "서울시 강남구 테헤란로 1",
            "101동 1001호",
            "06000",
            "홍길동",
            "010-2222-3333",
            "PASSWORD",
            "#1234",
            37.5,
            127.0,
            T);
    var masked = StaffOrderRules.mask(s, java.util.Set.of());
    assertThat(masked.roadAddress()).isEqualTo("서울시 강남구 테헤란로 1");
    assertThat(masked.postalCode()).isEqualTo("06000");
    assertThat(masked.entranceMethod()).isEqualTo("PASSWORD");
    assertThat(masked.detailAddress()).isEqualTo("***");
    assertThat(masked.receiverName()).isEqualTo("홍*동");
    assertThat(masked.receiverPhone()).isEqualTo("010-****-3333");
    assertThat(masked.entranceNote()).isNull();
    assertThat(masked.lat()).isNull();
    assertThat(masked.lng()).isNull();

    var phoneOnly = StaffOrderRules.mask(s, java.util.Set.of(StaffOrderRules.PiiField.PHONE));
    assertThat(phoneOnly.receiverPhone()).isEqualTo("010-2222-3333");
    assertThat(phoneOnly.receiverName()).isEqualTo("홍*동");
    assertThat(phoneOnly.detailAddress()).isEqualTo("***");

    var all = StaffOrderRules.mask(s, java.util.EnumSet.allOf(StaffOrderRules.PiiField.class));
    assertThat(all.detailAddress()).isEqualTo("101동 1001호");
    assertThat(all.entranceNote()).isEqualTo("#1234");
    assertThat(all.lat()).isEqualTo(37.5);
    assertThat(all.receiverName()).isEqualTo("홍길동");
  }
}
