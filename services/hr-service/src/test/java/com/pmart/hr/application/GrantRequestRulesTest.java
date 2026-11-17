package com.pmart.hr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pmart.common.web.PmartException;
import com.pmart.hr.application.EmployeeService.GrantRequest;
import com.pmart.hr.infrastructure.EmployeeAuthClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** 권한 요청 검사(HR-2): 같은 역할 두 줄·센터 규칙·이미 유효한 같은 권한(센터·기간 겹침, 연장). */
class GrantRequestRulesTest {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
  static final Instant NOW = TODAY.atTime(10, 0).atZone(SEOUL).toInstant();
  static final Map<String, EmployeeAuthClient.Role> ROLES =
      Map.of(
          "MFC_WORKER", new EmployeeAuthClient.Role("MFC_WORKER", true),
          "MFC_SUPERVISOR", new EmployeeAuthClient.Role("MFC_SUPERVISOR", true),
          "MD", new EmployeeAuthClient.Role("MD", false));

  static GrantRequest req(
      String role, List<String> centers, @Nullable LocalDate from, @Nullable LocalDate to) {
    return new GrantRequest(role, centers, from, to, null);
  }

  static Instant start(LocalDate d) {
    return d.atStartOfDay(SEOUL).toInstant();
  }

  static EmployeeAuthClient.Grant existing(
      String id, String role, List<String> centers, LocalDate from, @Nullable LocalDate lastDay) {
    return new EmployeeAuthClient.Grant(
        id, role, centers, start(from), lastDay == null ? null : start(lastDay.plusDays(1)));
  }

  static void check(List<GrantRequest> grants, List<EmployeeAuthClient.Grant> current) {
    GrantRequestRules.checkNotActive(
        grants, current, ROLES, TODAY.minusYears(1), null, NOW, SEOUL, "grants");
  }

  @Test
  void duplicateRoleInOneRequest() {
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () ->
                GrantRequestRules.noDuplicateRoles(
                    List.of(
                        req("MFC_WORKER", List.of("A"), null, null),
                        req("MFC_WORKER", List.of("B"), null, null)),
                    "grants"));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(e.details().getFirst().field()).isEqualTo("grants");
  }

  @Test
  void centerRuleAndUnknownRole() {
    assertThat(
            catchThrowableOfType(
                    PmartException.class,
                    () ->
                        GrantRequestRules.checkCatalog(
                            List.of(req("MFC_WORKER", List.of(), null, null)), ROLES, "grants"))
                .details()
                .getFirst()
                .field())
        .isEqualTo("grants[0].centers");
    assertThat(
            catchThrowableOfType(
                    PmartException.class,
                    () ->
                        GrantRequestRules.checkCatalog(
                            List.of(req("MD", List.of("A"), null, null)), ROLES, "requestedGrants"))
                .details()
                .getFirst()
                .field())
        .isEqualTo("requestedGrants[0].centers");
    assertThat(
            catchThrowableOfType(
                    PmartException.class,
                    () ->
                        GrantRequestRules.checkCatalog(
                            List.of(req("NOPE", List.of(), null, null)), ROLES, "grants"))
                .details()
                .getFirst()
                .field())
        .isEqualTo("grants[0].role");
    assertThatCode(
            () ->
                GrantRequestRules.checkCatalog(
                    List.of(
                        req("MFC_WORKER", List.of("A"), null, null),
                        req("MD", List.of(), null, null)),
                    ROLES,
                    "grants"))
        .doesNotThrowAnyException();
  }

  @Test
  void activeSameRoleOverlappingCenterAndPeriodIsConflict() {
    var current = List.of(existing("AP-1:1", "MFC_WORKER", List.of("A"), TODAY, TODAY.plusDays(9)));
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () ->
                check(
                    List.of(req("MFC_WORKER", List.of("A", "B"), null, TODAY.plusDays(5))),
                    current));
    assertThat(e.code()).isEqualTo(GrantRequestRules.ALREADY_ACTIVE);
    assertThat(e.status().value()).isEqualTo(409);
    assertThat(e.details().getFirst().reason())
        .contains("existingGrantId=AP-1:1", "extension=false");
    assertThat(e.getMessage()).doesNotContain("연장");

    PmartException ext =
        catchThrowableOfType(
            PmartException.class,
            () -> check(List.of(req("MFC_WORKER", List.of("A"), null, null)), current));
    assertThat(ext.getMessage()).contains("연장");
    assertThat(ext.details().getFirst().reason()).contains("extension=true");
  }

  @Test
  void otherCenterAfterEndRevokedAndOtherRoleAreAllowed() {
    var current = List.of(existing("AP-1:1", "MFC_WORKER", List.of("A"), TODAY, TODAY.plusDays(9)));
    assertThatCode(() -> check(List.of(req("MFC_WORKER", List.of("B"), null, null)), current))
        .as("다른 센터")
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                check(List.of(req("MFC_WORKER", List.of("A"), TODAY.plusDays(10), null)), current))
        .as("기존 권한이 끝난 날(마지막 날 다음 날)부터")
        .doesNotThrowAnyException();
    assertThatCode(() -> check(List.of(req("MD", List.of(), null, null)), current))
        .as("다른 역할")
        .doesNotThrowAnyException();
    // 오늘 아침에 끝난 권한(회수 — 효력 끝 = 회수 시각)은 지금 이후로 겹치지 않는다
    var revoked =
        List.of(
            new EmployeeAuthClient.Grant(
                "AP-2:1",
                "MFC_WORKER",
                List.of("A"),
                start(TODAY.minusDays(3)),
                NOW.minusSeconds(60)));
    assertThatCode(() -> check(List.of(req("MFC_WORKER", List.of("A"), null, null)), revoked))
        .doesNotThrowAnyException();
  }

  @Test
  void nonCenterScopedRoleConflictsRegardlessOfCenters() {
    // 예전 자료 — 센터가 필요 없는 역할인데 센터가 붙은 grant
    var current = List.of(existing("AP-3:1", "MD", List.of("A"), TODAY.minusDays(1), null));
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () -> check(List.of(req("MD", List.of(), TODAY.plusDays(100), null)), current));
    assertThat(e.details().getFirst().reason()).contains("validUntil=none");
  }

  @Test
  void periodsOverlapLooksOnlyFromNow() {
    Instant now = Instant.parse("2026-11-17T00:00:00Z");
    Instant past = now.minusSeconds(3600);
    assertThat(
            GrantRequestRules.periodsOverlap(
                past.minusSeconds(60), past, past.minusSeconds(30), null, now))
        .as("둘 다 지금 전에 끝남")
        .isFalse();
    assertThat(GrantRequestRules.periodsOverlap(now, null, now.plusSeconds(10), null, now))
        .isTrue();
    assertThat(
            GrantRequestRules.periodsOverlap(
                now, now.plusSeconds(10), now.plusSeconds(10), null, now))
        .as("끝 = 다른 쪽 시작")
        .isFalse();
  }

  // ---- 역할·센터 섞임(2026-11-18 — CENTER_ROLE_MIX)

  static void mix(List<GrantRequest> grants, List<EmployeeAuthClient.Grant> current) {
    GrantRequestRules.checkCenterMix(
        grants, current, ROLES, TODAY.minusYears(1), null, NOW, SEOUL, "grants");
  }

  @Test
  void supervisorAtAPlusWorkerAtBIsMixed() {
    var current = List.of(existing("AP-1:1", "MFC_SUPERVISOR", List.of("A"), TODAY, null));
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () -> mix(List.of(req("MFC_WORKER", List.of("B"), null, null)), current));
    assertThat(e.code()).isEqualTo("CENTER_ROLE_MIX");
    assertThat(e.details().getFirst().field()).isEqualTo("grants[0]");
    assertThat(e.details().getFirst().reason())
        .contains("existingGrantId=AP-1:1", "role=MFC_SUPERVISOR");
  }

  @Test
  void sameRoleOtherCenterOrSameCenterOtherRoleIsFine() {
    var current = List.of(existing("AP-1:1", "MFC_SUPERVISOR", List.of("A"), TODAY, null));
    assertThatCode(() -> mix(List.of(req("MFC_SUPERVISOR", List.of("B"), null, null)), current))
        .as("관리자@A + 관리자@B = 관리자@{A,B}")
        .doesNotThrowAnyException();
    assertThatCode(() -> mix(List.of(req("MFC_WORKER", List.of("A"), null, null)), current))
        .as("같은 센터의 다른 역할")
        .doesNotThrowAnyException();
    assertThatCode(() -> mix(List.of(req("MD", List.of(), null, null)), current))
        .as("센터 없는 역할")
        .doesNotThrowAnyException();
  }

  @Test
  void widerSupervisorPlusNarrowWorkerIsMixed() {
    var current = List.of(existing("AP-1:1", "MFC_SUPERVISOR", List.of("A", "B"), TODAY, null));
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () -> mix(List.of(req("MFC_WORKER", List.of("A"), null, null)), current));
    assertThat(e.code()).as("B 에서 작업자가 됨").isEqualTo("CENTER_ROLE_MIX");
  }

  @Test
  void nonOverlappingPeriodsAreFine() {
    var current =
        List.of(
            existing(
                "AP-1:1", "MFC_SUPERVISOR", List.of("A"), TODAY.minusDays(5), TODAY.plusDays(9)));
    assertThatCode(
            () -> mix(List.of(req("MFC_WORKER", List.of("B"), TODAY.plusDays(10), null)), current))
        .as("관리자@A 가 끝난 다음 날부터 작업자@B")
        .doesNotThrowAnyException();
  }

  @Test
  void mixedWithinOneRequestIs400() {
    PmartException e =
        catchThrowableOfType(
            PmartException.class,
            () ->
                mix(
                    List.of(
                        req("MFC_SUPERVISOR", List.of("A"), null, null),
                        req("MFC_WORKER", List.of("B"), null, null)),
                    List.of()));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(e.details().getFirst().field()).isEqualTo("grants[0]");
  }
}
