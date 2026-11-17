package com.pmart.employeeauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 역할·센터 섞임(2026-11-18 — permissions.md §9 큰 것 2). */
class CenterRoleMixTest {

  static final Instant NOW = Instant.parse("2026-11-18T01:00:00Z");
  static final GrantOverlap.Period ALWAYS = new GrantOverlap.Period(NOW.minusSeconds(86400), null);

  static CenterRoleMix.Entry e(String ref, String role, String... centers) {
    return new CenterRoleMix.Entry(ref, role, role.startsWith("MFC_"), Set.of(centers), ALWAYS);
  }

  static boolean scoped(String role) {
    return role.startsWith("MFC_");
  }

  @Test
  void supervisorAtAWorkerAtB() {
    assertThat(
            CenterRoleMix.conflict(
                e("n", "MFC_WORKER", "B"), List.of(e("g1", "MFC_SUPERVISOR", "A")), NOW))
        .isEqualTo("g1");
  }

  @Test
  void fineCombinations() {
    var sup = List.of(e("g1", "MFC_SUPERVISOR", "A"));
    assertThat(CenterRoleMix.conflict(e("n", "MFC_SUPERVISOR", "B"), sup, NOW))
        .as("같은 역할 다른 센터")
        .isNull();
    assertThat(CenterRoleMix.conflict(e("n", "MFC_WORKER", "A"), sup, NOW))
        .as("같은 센터 다른 역할")
        .isNull();
    assertThat(CenterRoleMix.conflict(e("n", "MD"), sup, NOW)).as("센터 없는 역할은 보지 않음").isNull();
    assertThat(
            CenterRoleMix.conflict(
                e("n", "MFC_WORKER", "A", "B"),
                List.of(e("g1", "MFC_SUPERVISOR", "A"), e("g2", "MFC_SUPERVISOR", "B")),
                NOW))
        .as("관리자@A + 관리자@B + 작업자@{A,B}")
        .isNull();
  }

  @Test
  void widerSupervisorNarrowWorker() {
    assertThat(
            CenterRoleMix.conflict(
                e("n", "MFC_WORKER", "A"), List.of(e("g1", "MFC_SUPERVISOR", "A", "B")), NOW))
        .isEqualTo("g1");
  }

  @Test
  void accountValuesCount() {
    // 계정 값: 역할 CS_AGENT(센터 지정 아님) + 센터 B → 관리자@A 가 B 까지 퍼진다
    var account =
        CenterRoleMix.accountEntries(List.of("CS_AGENT"), List.of("B"), CenterRoleMixTest::scoped);
    assertThat(CenterRoleMix.conflict(e("n", "MFC_SUPERVISOR", "A"), account, NOW))
        .isEqualTo(CenterRoleMix.ACCOUNT_REF);
    assertThat(
            CenterRoleMix.accountEntries(List.of("CS_AGENT"), List.of(), CenterRoleMixTest::scoped))
        .as("센터 없는 비센터 역할만이면 볼 것 없음")
        .isEmpty();
    var mfcAccount =
        CenterRoleMix.accountEntries(
            List.of("MFC_WORKER"), List.of("A"), CenterRoleMixTest::scoped);
    assertThat(CenterRoleMix.conflict(e("n", "MFC_SUPERVISOR", "A"), mfcAccount, NOW)).isNull();
  }

  @Test
  void onlyOverlappingPeriods() {
    var ended =
        new CenterRoleMix.Entry(
            "g1",
            "MFC_SUPERVISOR",
            true,
            Set.of("A"),
            new GrantOverlap.Period(NOW.minusSeconds(86400), NOW.plusSeconds(3600)));
    var later =
        new CenterRoleMix.Entry(
            "n",
            "MFC_WORKER",
            true,
            Set.of("B"),
            new GrantOverlap.Period(NOW.plusSeconds(3600), null));
    assertThat(CenterRoleMix.conflict(later, List.of(ended), NOW)).as("관리자@A 가 끝난 뒤부터").isNull();
  }
}
