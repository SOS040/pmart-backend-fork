package com.pmart.employeeauth.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 직원 역할 목록(BR-57 — 인사팀 백오피스의 "요청 권한 역할" 고르기). 정본은 설정 {@code pmart.employee-auth.roles}(순서)·{@code
 * role-info}(표시 이름·센터 지정). 비밀이 아니므로 OPERATOR 면 역할과 상관없이 본다.
 */
@Service
public class EmployeeRoleCatalog {

  private final EmployeeAuthProperties props;

  public EmployeeRoleCatalog(EmployeeAuthProperties props) {
    this.props = props;
  }

  public List<EmployeeAuthProperties.RoleView> list() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원 토큰이 필요합니다.");
    }
    return props.roleCatalog();
  }
}
