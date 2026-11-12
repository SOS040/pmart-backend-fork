package com.pmart.employeeauth.application;

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
 *   <li>ACTIVE 가 아니게 바꾸면 모든 세션을 끝낸다. 이미 나간 access token 즉시 차단(SubjectBlocked)은 5단계.
 * </ul>
 */
@Service
public class EmployeeAdminService {

  public static final String ADMIN_ROLE = "OPERATOR_ADMIN";

  private static final Logger log = LoggerFactory.getLogger(EmployeeAdminService.class);

  public record Change(String state, List<String> roles, List<String> centers, String reason) {}

  private final EmployeeAuthStore store;
  private final EmployeeAuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public EmployeeAdminService(
      EmployeeAuthStore store, EmployeeAuthProperties props, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public AccountRow get(String operatorId) {
    requireAdmin();
    return store
        .account(operatorId, false)
        .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다."));
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
              if (!EmployeeLoginService.ACTIVE.equals(change.state())) {
                store.revokeAllSessions(operatorId, "ACCOUNT_" + change.state(), now);
              }
              return store.account(operatorId, false).orElseThrow();
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
