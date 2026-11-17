package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 직원 로그인 상태·역할·권한 센터의 관리 경로(OPERATOR_ADMIN). ADR-0056 5단계(hr-service 사건·approval 권한 승인 연동) 전까지 운영자
 * 이전과 관리자 보정에 쓴다. 모든 변경은 변경 기록(누가·왜·전후)에 남긴다.
 *
 * <ul>
 *   <li>역할은 설정의 역할 목록에 있는 것만.
 *   <li>이미 있는 자기 계정은 바꾸지 못한다(스스로 권한을 올리거나 내리지 않게).
 *   <li>ACTIVE 가 아니게 바꾸면 모든 세션을 끝내고 SubjectBlocked 로 이미 나간 access token 도 바로 막는다.
 * </ul>
 */
@Service
public class EmployeeAdminService {

  public static final String ADMIN_ROLE = "OPERATOR_ADMIN";
  static final String HR_ADMIN_ROLE = "HR_ADMIN";

  private static final Logger log = LoggerFactory.getLogger(EmployeeAdminService.class);

  public record Change(String state, List<String> roles, List<String> centers, String reason) {}

  /** 조회 결과 — 계정 값, 지금 유효한 역할·센터(토큰과 같은 계산), 승인된 권한 목록(0.7.0). */
  public record View(
      AccountRow account,
      EmployeeAccess.Access effective,
      List<EmployeeAuthStore.GrantRow> grants) {}

  private final EmployeeAuthStore store;
  private final SubjectBlocks blocks;
  private final GrantEnds grantEnds;
  private final EmployeeAuthProperties props;
  private final TransactionTemplate tx;
  private final EmployeeAccess access;
  private final Clock clock;

  /**
   * 관리 경로({@code PUT /employees/{id}})로 역할·센터를 바로 바꿀 수 있나(권한 빈틈 정리 2026-11-17, 사용자 "(가)"). 기본 꺼짐 —
   * 역할은 인사 요청 → 승인(GRANT)으로 준다. LAN 은 시험 도구(staff_auth.py·Seed.java)가 시험 계정 역할을 맞추느라 켠다. 꺼져 있어도
   * 상태(정지 등) 변경은 된다.
   */
  private final boolean directRoleEdit;

  /** 관리 경로로는 절대 새로 줄 수 없는 역할 — 승인(GRANT)으로만. */
  static final java.util.Set<String> APPROVAL_ONLY_ROLES =
      java.util.Set.of("OPERATOR_ADMIN", "HR_ADMIN");

  /**
   * 관리 경로의 역할·센터 변경 검사. 관리자 역할을 새로 넣으면 항상 403, 그 밖 역할·센터 변경은 {@code enabled} 일 때만(빼는 것도 변경 — 회수는
   * grant-revocations).
   */
  static void checkRoleEdit(
      boolean enabled,
      List<String> beforeRoles,
      List<String> beforeCenters,
      List<String> roles,
      List<String> centers) {
    List<String> addedAdmin =
        roles.stream()
            .filter(r -> APPROVAL_ONLY_ROLES.contains(r) && !beforeRoles.contains(r))
            .toList();
    if (!addedAdmin.isEmpty()) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, String.join(", ", addedAdmin) + " 역할은 권한 요청 → 승인으로만 줄 수 있습니다.");
    }
    boolean changed =
        !new java.util.TreeSet<>(beforeRoles).equals(new java.util.TreeSet<>(roles))
            || !new java.util.TreeSet<>(beforeCenters).equals(new java.util.TreeSet<>(centers));
    if (changed && !enabled) {
      throw new PmartException(
          ErrorCode.FORBIDDEN,
          "역할·센터는 권한 요청 → 승인으로 바꿉니다(관리 경로는 상태만 — pmart.employee-auth.direct-role-edit 꺼짐).");
    }
  }

  public EmployeeAdminService(
      EmployeeAuthStore store,
      SubjectBlocks blocks,
      GrantEnds grantEnds,
      EmployeeAuthProperties props,
      TransactionTemplate tx,
      EmployeeAccess access,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${pmart.employee-auth.direct-role-edit:false}")
          boolean directRoleEdit) {
    this.directRoleEdit = directRoleEdit;
    this.store = store;
    this.access = access;
    this.blocks = blocks;
    this.grantEnds = grantEnds;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * 직원 로그인 상태·역할·권한 센터 조회 — OPERATOR_ADMIN, 인사팀 HR_ADMIN(0.7.0, 2026-11-15 — 인사팀 앱 "권한 관리" 메뉴에서 지금
   * 역할·센터를 본다, BO-01). 바꾸기({@link #put})는 OPERATOR_ADMIN 만.
   */
  public View get(String operatorId) {
    requireReader();
    AccountRow a =
        store
            .account(operatorId, false)
            .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다."));
    return view(a);
  }

  /** 계정 행 → 조회 결과(유효 역할·센터, grant 목록). */
  public View view(AccountRow a) {
    return new View(a, access.of(a, clock.instant()), store.grants(a.operatorId()));
  }

  /** 관리자 권한(OPERATOR_ADMIN·HR_ADMIN)은 운영 관리자만 회수한다(2026-11-15 사용자 결정 — 인사팀 앱은 이 둘을 못 뺀다). */
  static final java.util.Set<String> ADMIN_ONLY_REVOKE =
      java.util.Set.of(ADMIN_ROLE, HR_ADMIN_ROLE);

  /**
   * 권한 회수(BO-01, 2026-11-15 사용자 결정 — <b>승인 없이 즉시</b>, 사유 필수·기록). OPERATOR_ADMIN·HR_ADMIN. 자기 권한은 못
   * 뺀다, OPERATOR_ADMIN·HR_ADMIN 역할 grant 는 OPERATOR_ADMIN 만. 효력 끝 = 지금, 그 직원의 모든 세션을 끊고
   * SubjectBlocked(이미 나간 access token 도 바로 401) — 다시 로그인하면 남은 권한으로 토큰이 나온다. 같은 트랜잭션에서 {@code
   * GrantEnded}(REVOKED, EA-1 — 사유 글은 싣지 않음). 이미 회수한 grant 는 지금 상태(멱등), 이미 기한이 끝난 grant 는 409. 계정에
   * 직접 둔 역할(관리 경로 값)은 여기서 다루지 않는다 — 관리 경로 PUT(OPERATOR_ADMIN).
   */
  public View revokeGrant(String operatorId, String grantId, String reason, String sourceSystem) {
    PmartPrincipal p = requireReaderPrincipal();
    if (operatorId.equals(p.subject())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "자기 권한은 회수할 수 없습니다.");
    }
    if (store.account(operatorId, false).isEmpty()) {
      throw PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다.");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    boolean revoked =
        Boolean.TRUE.equals(
            tx.execute(
                s -> {
                  EmployeeAuthStore.GrantRow g =
                      store
                          .lockGrant(operatorId, grantId)
                          .orElseThrow(
                              () ->
                                  PmartException.notFound(
                                      "GRANT_NOT_FOUND", "이 직원의 권한이 아닙니다: " + grantId));
                  if (ADMIN_ONLY_REVOKE.contains(g.role()) && !p.hasRole(ADMIN_ROLE)) {
                    throw new PmartException(
                        ErrorCode.FORBIDDEN, g.role() + " 권한은 운영 관리자(OPERATOR_ADMIN)만 회수할 수 있습니다.");
                  }
                  if (g.revokedAt() != null) {
                    return false; // 이미 회수 — 지금 상태(멱등)
                  }
                  if (g.validUntil() != null && !g.validUntil().isAfter(now)) {
                    throw PmartException.invalidState("이미 기한이 끝난 권한입니다.");
                  }
                  store.revokeGrant(grantId, now, p.subject(), reason, sourceSystem);
                  store.revokeAllSessions(operatorId, "GRANT_REVOKED", now);
                  long version =
                      store.account(operatorId, false).map(AccountRow::version).orElse(0L);
                  EventEnvelope.Actor actor =
                      new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject());
                  blocks.blockAll(operatorId, version, now, "SESSIONS_REVOKED", actor);
                  // EA-1: 기간 전 종료 사건(사유 글은 DB 에만) — mfc-capacity 작업자 권한 기간
                  grantEnds.ended(
                      grantId,
                      g.approvalId(),
                      operatorId,
                      g.role(),
                      g.centers(),
                      now,
                      GrantEnds.REVOKED,
                      actor);
                  return true;
                }));
    if (revoked) {
      log.info("권한 회수: operatorId={} grantId={} by={}", operatorId, grantId, p.subject());
    }
    return view(
        store
            .account(operatorId, false)
            .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다.")));
  }

  public AccountRow put(String operatorId, Change change, String sourceSystem) {
    PmartPrincipal admin = requireAdmin();
    List<String> roles = change.roles().stream().distinct().sorted().toList();
    List<String> centers = change.centers().stream().distinct().sorted().toList();
    List<String> unknown = roles.stream().filter(r -> !props.roles().contains(r)).toList();
    if (!unknown.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "알 수 없는 역할: " + String.join(", ", unknown),
          List.of(new PmartException.Detail("roles", "pmart.employee-auth.roles 에 없음")));
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    AccountRow after =
        tx.execute(
            s -> {
              AccountRow before = store.account(operatorId, true).orElse(null);
              if (before != null && operatorId.equals(admin.subject())) {
                throw new PmartException(ErrorCode.FORBIDDEN, "자기 계정의 상태·권한은 바꿀 수 없습니다.");
              }
              checkRoleEdit(
                  directRoleEdit,
                  before == null ? List.of() : before.roles(),
                  before == null ? List.of() : before.centers(),
                  roles,
                  centers);
              if (before == null) {
                store.insertAccount(operatorId, change.state(), roles, centers, now);
              } else if (!before.state().equals(change.state())
                  || !before.roles().equals(roles)
                  || !before.centers().equals(centers)) {
                store.updateAccount(operatorId, change.state(), roles, centers, now);
              } else {
                return before; // 같은 값 — 기록 없이 그대로(멱등)
              }
              store.insertChange(
                  operatorId,
                  admin.subject(),
                  sourceSystem,
                  change.reason(),
                  before,
                  change.state(),
                  roles,
                  centers,
                  now);
              AccountRow updated = store.account(operatorId, false).orElseThrow();
              if (!EmployeeLoginService.ACTIVE.equals(change.state())
                  && (before == null || EmployeeLoginService.ACTIVE.equals(before.state()))) {
                store.revokeAllSessions(operatorId, "ACCOUNT_" + change.state(), now);
                // 이미 나간 직원 토큰(15분)도 바로 막는다
                blocks.block(
                    operatorId,
                    updated.version(),
                    now,
                    new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, admin.subject()));
              }
              return updated;
            });
    log.info(
        "직원 변경: operatorId={} state={} roles={} centers={} by={}",
        operatorId,
        change.state(),
        roles,
        centers,
        admin.subject());
    return Objects.requireNonNull(after);
  }

  private static void requireReader() {
    requireReaderPrincipal();
  }

  private static PmartPrincipal requireReaderPrincipal() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR
        || !(p.hasRole(ADMIN_ROLE) || p.hasRole(HR_ADMIN_ROLE))) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, ADMIN_ROLE + " 또는 " + HR_ADMIN_ROLE + " 역할이 필요합니다.");
    }
    return p;
  }

  private static PmartPrincipal requireAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.hasRole(ADMIN_ROLE)) {
      throw new PmartException(ErrorCode.FORBIDDEN, ADMIN_ROLE + " 역할이 필요합니다.");
    }
    return p;
  }
}
