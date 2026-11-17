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
import java.util.TreeSet;
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
 *       받는다. 겹치면서 더 늦게 끝나는 요청(기간 연장)도 409 — 연장은 지금 권한이 끝나는 날부터 시작하는 새 요청으로(HR-2 ①, 2026-11-18).
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
                        EmployeeAuthClient.ACCOUNT.equals(x.grantId()) ? "none" : x.validFrom(),
                        x.validUntil() == null ? "none" : x.validUntil().toString(),
                        ext)));
      }
    }
    if (first == null) {
      return;
    }
    boolean account = EmployeeAuthClient.ACCOUNT.equals(first.grantId());
    StringBuilder m =
        new StringBuilder(account ? "계정에 직접 들어 있는 같은 권한이 있습니다: " : "이미 유효하거나 시작 전인 같은 권한이 있습니다: ")
            .append(first.role())
            .append(first.centers().isEmpty() ? "" : "@" + String.join(",", first.centers()));
    if (account) {
      // 2026-11-18 HR-2 ③ — 관리 경로·이전 계정의 역할. 승인 권한으로 또 주면 회수해도 계정 값이 남는다
      m.append(" (관리 경로·이전 계정 — 기한 없음). 승인 권한으로 다시 줄 필요가 없습니다.");
    } else {
      m.append(" (")
          .append(first.validFrom().atZone(zone).toLocalDate())
          .append(" ~ ")
          .append(
              first.validUntil() == null
                  ? "기한 없음"
                  : first.validUntil().minusNanos(1).atZone(zone).toLocalDate().toString())
          .append("). 그 권한이 끝난 다음 날부터로 요청하거나, 필요 없으면 먼저 회수하세요.");
    }
    if (extension) {
      m.append(" 기간을 늘리려면 지금 권한이 끝나는 날부터 시작하는 새 요청으로 하세요(인사팀 앱 \"연장\").");
    }
    throw new PmartException(ALREADY_ACTIVE, HttpStatus.CONFLICT, m.toString(), false, details);
  }

  public static final String CENTER_ROLE_MIX = "CENTER_ROLE_MIX";

  /** 섞임 판정의 한 줄 — 기존 grant({@code grantId} 있음) 또는 요청 행({@code index}). */
  private record Item(
      @Nullable String grantId,
      int index,
      String role,
      boolean centerScoped,
      Set<String> centers,
      Instant from,
      @Nullable Instant until) {}

  /**
   * 역할·센터 섞임(2026-11-18, permissions.md §9 큰 것 2 — employee-auth-service {@code CenterRoleMix} 와 같은
   * 규칙). 직원 토큰은 역할과 센터를 따로 모아 싣기 때문에, 같은 때 유효한 권한들 안에서 <b>센터 지정 역할 하나하나의 센터 = 센터 전체</b>여야 한다(A 관리자
   * + B 작업자 → B 에서도 관리자가 됨). 요청 행마다 기간이 지금 이후로 겹치는 기존 grant·다른 요청 행과 묶어 본다. 기존 grant 와 섞이면 409
   * {@code CENTER_ROLE_MIX}(details 에 기존 grant), 요청 행끼리만 섞이면 400. 계정에 직접 둔 역할(이전 계정·관리 경로)은 여기서 못
   * 본다 — employee-auth-service 가 반영 때 거른다(회신 REJECTED CENTER_ROLE_MIX).
   *
   * @param existing employee-auth-service 의 지금 기준으로 끝나지 않은 grant(등록이면 빈 목록)
   */
  public static void checkCenterMix(
      List<GrantRequest> grants,
      List<EmployeeAuthClient.Grant> existing,
      Map<String, EmployeeAuthClient.Role> roles,
      LocalDate start,
      @Nullable LocalDate end,
      Instant now,
      ZoneId zone,
      String field) {
    List<Item> items = new ArrayList<>();
    for (EmployeeAuthClient.Grant x : existing) {
      items.add(
          new Item(
              x.grantId(),
              -1,
              x.role(),
              scoped(roles, x.role()),
              new TreeSet<>(x.centers()),
              x.validFrom(),
              x.validUntil()));
    }
    for (int i = 0; i < grants.size(); i++) {
      GrantRequest g = grants.get(i);
      LocalDate from = g.validFrom() != null ? g.validFrom() : start;
      LocalDate to = g.validTo() != null ? g.validTo() : end;
      items.add(
          new Item(
              null,
              i,
              g.role(),
              scoped(roles, g.role()),
              new TreeSet<>(g.centers()),
              from.atStartOfDay(zone).toInstant(),
              to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant()));
    }
    for (Item c : items) {
      if (c.grantId() != null || (!c.centerScoped() && c.centers().isEmpty())) {
        continue;
      }
      List<Item> group = new ArrayList<>();
      group.add(c);
      for (Item o : items) {
        if (o != c && periodsOverlap(c.from(), c.until(), o.from(), o.until(), now)) {
          group.add(o);
        }
      }
      if (group.size() == 1 || !mixed(group)) {
        continue;
      }
      Item with =
          group.stream()
              .filter(o -> o != c && o.grantId() != null && !o.role().equals(c.role()))
              .findFirst()
              .orElse(
                  group.stream()
                      .filter(o -> o != c && o.grantId() != null)
                      .findFirst()
                      .orElse(null));
      String m =
          "센터마다 다른 MFC 역할이 생깁니다: "
              + c.role()
              + "@"
              + String.join(",", c.centers())
              + (with == null
                  ? "(같은 요청의 다른 행과)"
                  : (EmployeeAuthClient.ACCOUNT.equals(with.grantId())
                          ? " ↔ 계정에 직접 든 값 "
                          : " ↔ 지금 권한 ")
                      + (with.role().isEmpty() ? "(역할 없음)" : with.role())
                      + "@"
                      + String.join(",", with.centers()))
              + ". 직원 토큰은 역할과 센터를 따로 실어 다른 센터에서도 그 역할이 됩니다 — MFC 역할은 모든 권한 센터에 같게 두세요(다른 센터 권한을"
              + " 먼저 회수하거나 같은 센터로 요청).";
      if (with == null) {
        throw invalid(field + "[" + c.index() + "]", m);
      }
      throw new PmartException(
          CENTER_ROLE_MIX,
          HttpStatus.CONFLICT,
          m,
          false,
          List.of(
              new PmartException.Detail(
                  field + "[" + c.index() + "]",
                  "existingGrantId=%s;role=%s;centers=%s"
                      .formatted(with.grantId(), with.role(), String.join(",", with.centers())))));
    }
  }

  private static boolean scoped(Map<String, EmployeeAuthClient.Role> roles, String role) {
    EmployeeAuthClient.Role r = roles.get(role);
    return r != null && r.centerScoped();
  }

  /** 한 토큰에 모았을 때 센터 지정 역할마다 센터가 전체와 다른가. */
  private static boolean mixed(List<Item> items) {
    Set<String> all = new TreeSet<>();
    Map<String, Set<String>> byRole = new java.util.HashMap<>();
    for (Item i : items) {
      all.addAll(i.centers());
      if (i.centerScoped()) {
        byRole.computeIfAbsent(i.role(), k -> new TreeSet<>()).addAll(i.centers());
      }
    }
    return byRole.values().stream().anyMatch(x -> !x.equals(all));
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
