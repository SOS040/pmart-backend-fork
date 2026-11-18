package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customer.domain.ConsentRules.Change;
import com.pmart.customer.domain.ConsentRules.Stored;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsentRulesTest {

  static final Instant T0 = Instant.parse("2026-11-17T01:00:00Z");
  static final Instant T1 = Instant.parse("2026-11-17T02:00:00Z");

  static Map<ConsentItem, String> current() {
    Map<ConsentItem, String> m = new EnumMap<>(ConsentItem.class);
    m.put(ConsentItem.TERMS_OF_SERVICE, "2026-11-17");
    m.put(ConsentItem.PRIVACY_POLICY, "2026-11-17");
    m.put(ConsentItem.AGE_OVER_14, "1");
    m.put(ConsentItem.MARKETING, "2026-11-17");
    m.put(ConsentItem.LOCATION_BASED_SERVICE, "2026-11-19");
    return m;
  }

  static Stored stored(ConsentItem item, boolean agreed, String version) {
    return new Stored(item, agreed, version, agreed ? T0 : null, null, "APP", 1, T0);
  }

  @Test
  void nothingChosenMeansAllRequiredMissingAndMarketingUndecided() {
    ConsentRules.Status s = ConsentRules.status(current(), Map.of());
    assertThat(s.requiredOutdated()).isTrue();
    assertThat(s.missingRequired())
        .containsExactly(
            "TERMS_OF_SERVICE:2026-11-17", "PRIVACY_POLICY:2026-11-17", "AGE_OVER_14:1");
    assertThat(s.undecidedOptional()).containsExactly("MARKETING:2026-11-17");
  }

  @Test
  void requiredAgreedAtCurrentVersionIsSatisfiedAndDeclinedMarketingIsDecided() {
    Map<ConsentItem, Stored> st = new EnumMap<>(ConsentItem.class);
    st.put(ConsentItem.TERMS_OF_SERVICE, stored(ConsentItem.TERMS_OF_SERVICE, true, "2026-11-17"));
    st.put(ConsentItem.PRIVACY_POLICY, stored(ConsentItem.PRIVACY_POLICY, true, "2026-11-17"));
    st.put(ConsentItem.AGE_OVER_14, stored(ConsentItem.AGE_OVER_14, true, "1"));
    st.put(ConsentItem.MARKETING, stored(ConsentItem.MARKETING, false, "2026-11-17"));
    ConsentRules.Status s = ConsentRules.status(current(), st);
    assertThat(s.requiredOutdated()).isFalse();
    assertThat(s.undecidedOptional()).isEmpty(); // 거부도 결정이다 — 다시 묻지 않는다
  }

  @Test
  void versionBumpAsksAgain() {
    Map<ConsentItem, Stored> st = new EnumMap<>(ConsentItem.class);
    st.put(ConsentItem.TERMS_OF_SERVICE, stored(ConsentItem.TERMS_OF_SERVICE, true, "2026-01-01"));
    st.put(ConsentItem.PRIVACY_POLICY, stored(ConsentItem.PRIVACY_POLICY, true, "2026-11-17"));
    st.put(ConsentItem.AGE_OVER_14, stored(ConsentItem.AGE_OVER_14, true, "1"));
    st.put(ConsentItem.MARKETING, stored(ConsentItem.MARKETING, true, "2026-01-01"));
    ConsentRules.Status s = ConsentRules.status(current(), st);
    assertThat(s.missingRequired()).containsExactly("TERMS_OF_SERVICE:2026-11-17");
    assertThat(s.undecidedOptional()).containsExactly("MARKETING:2026-11-17");
  }

  @Test
  void requiredCannotBeTurnedOffAndItemsAreNotRepeated() {
    assertThat(
            ConsentRules.checkRequest(
                List.of(new Change(ConsentItem.PRIVACY_POLICY, "2026-11-17", false))))
        .get()
        .extracting(ConsentRules.Violation::code)
        .isEqualTo("REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN");
    assertThat(
            ConsentRules.checkRequest(
                List.of(
                    new Change(ConsentItem.MARKETING, "2026-11-17", true),
                    new Change(ConsentItem.MARKETING, "2026-11-17", false))))
        .get()
        .extracting(ConsentRules.Violation::code)
        .isEqualTo("VALIDATION_FAILED");
    assertThat(
            ConsentRules.checkRequest(
                List.of(new Change(ConsentItem.MARKETING, "2026-11-17", false))))
        .isEmpty();
  }

  @Test
  void onlyCurrentVersionIsAccepted() {
    assertThat(
            ConsentRules.checkVersions(
                List.of(new Change(ConsentItem.TERMS_OF_SERVICE, "2026-01-01", true)), current()))
        .get()
        .extracting(ConsentRules.Violation::code)
        .isEqualTo("CONSENT_VERSION_OUTDATED");
    assertThat(
            ConsentRules.checkVersions(
                List.of(new Change(ConsentItem.AGE_OVER_14, "1", true)), current()))
        .isEmpty();
  }

  @Test
  void withdrawingKeepsLastAgreementAndDecliningIsNotWithdrawal() {
    Change off = new Change(ConsentItem.MARKETING, "2026-11-17", false);
    Change on = new Change(ConsentItem.MARKETING, "2026-11-17", true);
    // 동의 중 → 끄기: 철회 시각
    ConsentRules.Times t =
        ConsentRules.times(stored(ConsentItem.MARKETING, true, "2026-11-17"), off, T1);
    assertThat(t.agreedAt()).isEqualTo(T0);
    assertThat(t.withdrawnAt()).isEqualTo(T1);
    // 처음부터 거부: 철회 아님
    ConsentRules.Times declined = ConsentRules.times(null, off, T1);
    assertThat(declined.agreedAt()).isNull();
    assertThat(declined.withdrawnAt()).isNull();
    // 다시 켜기: 동의 시각 새로, 철회 시각 지움
    ConsentRules.Times again =
        ConsentRules.times(stored(ConsentItem.MARKETING, false, "2026-11-17"), on, T1);
    assertThat(again.agreedAt()).isEqualTo(T1);
    assertThat(again.withdrawnAt()).isNull();
    // 같은 값·같은 버전은 바꾸지 않는다
    assertThat(ConsentRules.unchanged(stored(ConsentItem.MARKETING, true, "2026-11-17"), on))
        .isTrue();
    assertThat(ConsentRules.unchanged(stored(ConsentItem.MARKETING, true, "2026-01-01"), on))
        .isFalse();
  }

  /** 위치기반서비스(기능 필수, 2026-11-19 BR-59)는 로그인 동의 판단(필수·선택 목록)에 들어가지 않는다. */
  @Test
  void locationBasedServiceIsNeitherMissingNorUndecided() {
    ConsentRules.Status s = ConsentRules.status(current(), Map.of());
    assertThat(s.missingRequired()).noneMatch(r -> r.startsWith("LOCATION_BASED_SERVICE"));
    assertThat(s.undecidedOptional()).containsExactly("MARKETING:2026-11-17");
  }

  /** 기기 결정: 계정에 현행 버전 결정이 없을 때만 판단·옮기기에 쓰고, 계정 값이 이긴다. 낡은 버전·기기 항목 아닌 것은 무시. */
  @Test
  void deviceDecisionsFillOnlyWhatTheAccountHasNotDecided() {
    Map<ConsentItem, Stored> device = new EnumMap<>(ConsentItem.class);
    device.put(ConsentItem.MARKETING, stored(ConsentItem.MARKETING, true, "2026-11-17"));
    device.put(
        ConsentItem.LOCATION_BASED_SERVICE,
        stored(ConsentItem.LOCATION_BASED_SERVICE, true, "2026-01-01")); // 낡은 버전
    // 계정에 없음 → 마케팅만 옮김, 판단에서도 결정된 것
    assertThat(ConsentRules.toMigrate(current(), Map.of(), device))
        .containsExactly(new ConsentRules.Change(ConsentItem.MARKETING, "2026-11-17", true));
    assertThat(
            ConsentRules.status(current(), ConsentRules.withDevice(current(), Map.of(), device))
                .undecidedOptional())
        .isEmpty();
    // 계정에 현행 버전 결정이 있으면 옮기지 않는다(계정 값이 이긴다)
    Map<ConsentItem, Stored> account =
        Map.of(ConsentItem.MARKETING, stored(ConsentItem.MARKETING, false, "2026-11-17"));
    assertThat(ConsentRules.toMigrate(current(), account, device)).isEmpty();
    assertThat(
            ConsentRules.withDevice(current(), account, device).get(ConsentItem.MARKETING).agreed())
        .isFalse();
  }

  @Test
  void deviceAcceptsOnlyMarketingAndLocation() {
    assertThat(
            ConsentRules.checkDevice(
                List.of(
                    new ConsentRules.Change(
                        ConsentItem.LOCATION_BASED_SERVICE, "2026-11-19", true))))
        .isEmpty();
    assertThat(
            ConsentRules.checkDevice(
                List.of(new ConsentRules.Change(ConsentItem.TERMS_OF_SERVICE, "2026-11-17", true))))
        .isPresent();
  }
}
