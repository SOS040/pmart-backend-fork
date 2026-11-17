package com.pmart.employeeauth.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * 역할·센터 섞임 판정(2026-11-18, permissions.md §9 큰 것 2 — 사용자 "일단 pc1만으로"). 직원 토큰은 역할과 센터를 따로 모아
 * 싣는다({@link EmployeeAccess}) — A 센터 관리자(MFC_SUPERVISOR@A) + B 센터 작업자(MFC_WORKER@B)면 토큰은 {@code
 * roles=[관리자, 작업자], centers=[A, B]} 이고 받는 서비스는 B 에서도 관리자로 본다. 토큰 모양은 그대로 두고, 그런 조합이 생기는 권한을 만들지
 * 않는다.
 *
 * <p>규칙: 같은 때에 함께 유효한 권한들 안에서, <b>센터 지정 역할(role-info center-scoped) 하나하나가 가진 센터 = 토큰에 실릴 센터 전체</b>
 * 여야 한다. 그래야 토큰의 (역할 × 센터) 조합이 실제로 받은 권한과 같다.
 *
 * <ul>
 *   <li>섞임: 관리자@A + 작업자@B, 관리자@{A,B} + 작업자@A(B 에서 작업자가 됨), 관리자@A + 센터 B 가 붙은 계정 값.
 *   <li>괜찮음: 관리자@A + 관리자@B(관리자@{A,B}), 관리자@A + 작업자@A, 센터 지정이 아닌 역할(센터 없음)과 함께.
 *   <li>"같은 때": 새 권한과 기간이 지금 이후로 겹치는 것만 본다({@link GrantOverlap#periodsOverlap}). 겹치는 것끼리는 서로 겹치지
 *       않아도 한 묶음으로 본다(보수적 — 드물게 괜찮은 요청도 거절할 수 있다).
 *   <li>새 권한이 센터 지정 역할도 아니고 센터도 없으면 보지 않는다 — 이미 섞인 예전 값이 상관없는 권한까지 막지 않게.
 * </ul>
 *
 * hr-service {@code GrantRequestRules} 가 요청 때 같은 규칙으로 먼저 거른다(409 {@code CENTER_ROLE_MIX}). 이 서비스는
 * 승인 반영({@link GrantApprovalHandler})·관리 경로 PUT({@link EmployeeAdminService})의 안전망.
 */
public final class CenterRoleMix {

  /** 오류·회신 거절 코드. hr-service 409 와 같은 이름. */
  public static final String CODE = "CENTER_ROLE_MIX";

  /** 계정에 직접 둔 값(관리 경로·이전한 계정)을 가리키는 ref. */
  public static final String ACCOUNT_REF = "account";

  private CenterRoleMix() {}

  /**
   * 권한 한 줄(또는 계정 값의 역할 하나). {@code role} null = 역할 없이 센터만 있는 계정 값.
   *
   * @param ref 충돌 상대를 알릴 때 쓰는 값(grantId 또는 {@link #ACCOUNT_REF})
   */
  public record Entry(
      String ref,
      @Nullable String role,
      boolean centerScoped,
      Set<String> centers,
      GrantOverlap.Period period) {}

  /** 계정 값 → 항목들(기한 없음). 센터 지정 역할도 센터도 없으면 빈 목록. */
  public static List<Entry> accountEntries(
      List<String> roles, List<String> centers, java.util.function.Predicate<String> centerScoped) {
    GrantOverlap.Period always = new GrantOverlap.Period(Instant.EPOCH, null);
    Set<String> c = new TreeSet<>(centers);
    List<Entry> out = new ArrayList<>();
    for (String r : roles) {
      boolean scoped = centerScoped.test(r);
      if (scoped || !c.isEmpty()) {
        out.add(new Entry(ACCOUNT_REF, r, scoped, c, always));
      }
    }
    if (roles.isEmpty() && !c.isEmpty()) {
      out.add(new Entry(ACCOUNT_REF, null, false, c, always));
    }
    return out;
  }

  /**
   * {@code candidate} 를 더하면 섞이는가. 섞이면 충돌 상대 ref(역할이 다른 첫 기존 항목, 없으면 첫 기존 항목), 아니면 null.
   *
   * @param existing 같은 직원의 지금 유효하거나 시작 전인 항목(계정 값 포함, candidate 자신은 빼고)
   */
  public static @Nullable String conflict(Entry candidate, List<Entry> existing, Instant now) {
    if (!candidate.centerScoped() && candidate.centers().isEmpty()) {
      return null;
    }
    List<Entry> group = new ArrayList<>();
    for (Entry e : existing) {
      if (GrantOverlap.periodsOverlap(candidate.period(), e.period(), now)) {
        group.add(e);
      }
    }
    if (group.isEmpty()) {
      return null;
    }
    List<Entry> all = new ArrayList<>(group);
    all.add(candidate);
    if (!mixed(all)) {
      return null;
    }
    for (Entry e : group) {
      if (e.role() == null || !e.role().equals(candidate.role())) {
        return e.ref();
      }
    }
    return group.get(0).ref();
  }

  /** 항목들을 한 토큰에 모았을 때 센터 지정 역할마다 센터가 전체와 다른가. */
  static boolean mixed(List<Entry> entries) {
    Set<String> all = new TreeSet<>();
    Map<String, Set<String>> byRole = new HashMap<>();
    for (Entry e : entries) {
      all.addAll(e.centers());
      if (e.centerScoped() && e.role() != null) {
        byRole.computeIfAbsent(e.role(), k -> new TreeSet<>()).addAll(e.centers());
      }
    }
    return byRole.values().stream().anyMatch(s -> !s.equals(all));
  }
}
