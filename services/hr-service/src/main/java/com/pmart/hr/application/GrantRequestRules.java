package com.pmart.hr.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.hr.application.EmployeeService.GrantRequest;
import com.pmart.hr.infrastructure.EmployeeAuthClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * 권한 요청 검사(2026-11-17 HR-2 — permissions.md §9-11·12, 사용자 수락 "백엔드부터"). 등록 {@code requestedGrants} 와
 * 재직 중 권한 추가·변경 요청 {@code grants} 에 같이 쓴다. 역할 목록·센터 지정·지금 권한의 정본은 employee-auth-service(내부 조회 —
 * {@link EmployeeAuthClient}).
 *
 * <ul>
 *   <li>한 요청 안에 같은 역할 두 줄 → 400(field = 목록 이름). 여러 센터는 한 줄의 centers 에 모은다.
 *   <li>모르는 역할 → 400(field {@code <목록>[i].role}). 센터 지정 역할(centerScoped)은 센터 1개 이상, 아닌 역할은 센터 없음 →
 *       400(field {@code <목록>[i].centers}).
 *   <li>이미 유효하거나 시작 전인 같은 권한 → 409 {@code GRANT_ALREADY_ACTIVE} — 역할 같음 + 센터 겹침(센터 지정이 아닌 역할은 센터와
 *       상관없이, 둘 다 센터 없음도 겹침) + 기간이 지금 이후로 겹침. 비운 기간 = 근무 기간(GrantRequested 와 같은 값), 기간은 시작일 00:00 ~
 *       마지막 날 다음 날 00:00(Asia/Seoul — employee-auth-service grant 와 같은 계산). 기존 권한이 끝난 뒤부터 시작하는 요청은
 *       받는다. 겹치면서 더 늦게 끝나는 요청(기간 연장)도 지금은 409 — 연장을 어떻게 받을지 결정 대기(HR-2 "정할 것").
 *       employee-auth-service 도 승인 반영 때 같은 규칙으로 새 줄을 만들지 않는다(안전망).
 * </ul>
 */
public final class GrantRequestRules {

  public static final String ALREADY_ACTIVE = "GRANT_ALREADY_ACTIVE";

  private GrantRequestRules() {}

  /** 같은 역할 두 줄(외부 조회 없이 먼저). */
  public static void noDuplicateRoles(List<GrantRequest> grants, String field) {
    Set<String> seen = new HashSet<>();
    for (GrantRequest g : grants) {
      if (!seen.add(g.role())) {
        String m = "한 요청에 같은 역할이 두 번 있습니다: " + g.role() + " — 여러 센터는 한 줄의 centers 에 모아 주세요.";
        throw invalid(field, m);
      }
    }
  }

  /** 모르는 역할·센터 규칙(역할 목록 = employee-auth-service). */
  public static void checkCatalog(
      List<GrantRequest> grants, Map<String, EmployeeAuthClient.Role> roles, String field) {
    for (int i = 0; i < grants.size(); i++) {
      GrantRequest g = grants.get(i);
      EmployeeAuthClient.Role role = roles.get(g.role());
      if (role == null) {
        throw invalid(field + "[" + i + "].role", "모르는 역할입니다: " + g.role() + "(역할 목록에 없음).");
      }
      if (role.centerScoped() && g.centers().isEmpty()) {
        throw invalid(
            field + "[" + i + "].centers", "센터 지정 역할(" + g.role() + ")은 권한 센터를 1개 이상 골라야 합니다.");
      }
      if (!role.centerScoped() && !g.centers().isEmpty()) {
        throw invalid(
            field + "[" + i + "].centers", "센터가 필요 없는 역할(" + g.role() + ")에는 권한 센터를 넣지 않습니다.");
      }
    }
  }

  /**
   * 이미 유효하거나 시작 전인 같은 권한(409). {@code start}·{@code end} 는 직원 근무 기간(비운 기간을 채운다).
   *
   * @param existing employee-auth-service 의 지금 기준으로 끝나지 않은 grant
   */
  public static void checkNotActive(
      List<GrantRequest> grants,
      List<EmployeeAuthClient.Grant> existing,
      Map<String, EmployeeAuthClient.Role> roles,
      LocalDate start,
      @Nullable LocalDate end,
      Instant now,
      ZoneId zone,
      String field) {
    List<PmartException.Detail> details = new ArrayList<>();
    EmployeeAuthClient.Grant first = null;
    boolean extension = false;
    for (int i = 0; i < grants.size(); i++) {
      GrantRequest g = grants.get(i);
      EmployeeAuthClient.Role role = roles.get(g.role());
      boolean centerScoped = role != null && role.centerScoped();
      LocalDate from = g.validFrom() != null ? g.validFrom() : start;
      LocalDate to = g.validTo() != null ? g.validTo() : end;
      Instant reqFrom = from.atStartOfDay(zone).toInstant();
      Instant reqUntil = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
      for (EmployeeAuthClient.Grant x : existing) {
        if (!x.role().equals(g.role())
            || !centersOverlap(centerScoped, x.centers(), g.centers())
            || !periodsOverlap(reqFrom, reqUntil, x.validFrom(), x.validUntil(), now)) {
          continue;
        }
        boolean ext = extendsBeyond(reqUntil, x.validUntil());
        extension |= ext;
        if (first == null) {
          first = x;
        }
        details.add(
            new PmartException.Detail(
                field + "[" + i + "]",
                "existingGrantId=%s;role=%s;centers=%s;validFrom=%s;validUntil=%s;extension=%s"
                    .formatted(
                        x.grantId(),
                        x.role(),
                        String.join(",", x.centers()),
                        x.validFrom(),
                        x.validUntil() == null ? "none" : x.validUntil().toString(),
                        ext)));
      }
    }
    if (first == null) {
      return;
    }
    StringBuilder m =
        new StringBuilder("이미 유효하거나 시작 전인 같은 권한이 있습니다: ")
            .append(first.role())
            .append(first.centers().isEmpty() ? "" : "@" + String.join(",", first.centers()))
            .append(" (")
            .append(first.validFrom().atZone(zone).toLocalDate())
            .append(" ~ ")
            .append(
                first.validUntil() == null
                    ? "기한 없음"
                    : first.validUntil().minusNanos(1).atZone(zone).toLocalDate().toString())
            .append("). 그 권한이 끝난 다음 날부터로 요청하거나, 필요 없으면 먼저 회수하세요.");
    if (extension) {
      m.append(" 기간 연장(종료일 늘리기)은 아직 별도 기능이 없습니다(결정 대기).");
    }
    throw new PmartException(ALREADY_ACTIVE, HttpStatus.CONFLICT, m.toString(), false, details);
  }

  /** 센터 지정 역할은 센터가 하나라도 같거나 둘 다 없음, 아닌 역할은 늘 겹침. */
  static boolean centersOverlap(boolean centerScoped, List<String> a, List<String> b) {
    if (!centerScoped || (a.isEmpty() && b.isEmpty())) {
      return true;
    }
    Set<String> s = new HashSet<>(a);
    return b.stream().anyMatch(s::contains);
  }

  /** {@code [max(시작들, 지금), min(끝들))} 이 비어 있지 않은가(끝 null = 기한 없음). */
  static boolean periodsOverlap(
      Instant aFrom,
      @Nullable Instant aUntil,
      Instant bFrom,
      @Nullable Instant bUntil,
      Instant now) {
    Instant lo = aFrom.isAfter(bFrom) ? aFrom : bFrom;
    lo = lo.isAfter(now) ? lo : now;
    Instant hi;
    if (aUntil == null) {
      hi = bUntil;
    } else if (bUntil == null) {
      hi = aUntil;
    } else {
      hi = aUntil.isBefore(bUntil) ? aUntil : bUntil;
    }
    return hi == null || lo.isBefore(hi);
  }

  static boolean extendsBeyond(@Nullable Instant requestedUntil, @Nullable Instant existingUntil) {
    if (existingUntil == null) {
      return false;
    }
    return requestedUntil == null || requestedUntil.isAfter(existingUntil);
  }

  private static PmartException invalid(String field, String message) {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED, message, List.of(new PmartException.Detail(field, message)));
  }
}
