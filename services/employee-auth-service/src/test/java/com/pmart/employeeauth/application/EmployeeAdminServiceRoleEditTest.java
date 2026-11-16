package com.pmart.employeeauth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 관리 경로 역할·센터 변경 검사(권한 빈틈 정리 2026-11-17, 사용자 "(가)"). */
class EmployeeAdminServiceRoleEditTest {

  static final List<String> WORKER = List.of("MFC_WORKER");
  static final List<String> C1 = List.of("MFC-1");

  @Test
  void switchOffAllowsStateOnlyChanges() {
    assertThatCode(() -> EmployeeAdminService.checkRoleEdit(false, WORKER, C1, WORKER, C1))
        .doesNotThrowAnyException();
  }

  @Test
  void switchOffRejectsRoleOrCenterChanges() {
    assertThatThrownBy(
            () ->
                EmployeeAdminService.checkRoleEdit(
                    false, WORKER, C1, List.of("MFC_SUPERVISOR"), C1))
        .isInstanceOf(PmartException.class);
    assertThatThrownBy(
            () -> EmployeeAdminService.checkRoleEdit(false, WORKER, C1, WORKER, List.of("MFC-2")))
        .isInstanceOf(PmartException.class);
    assertThatThrownBy(
            () -> EmployeeAdminService.checkRoleEdit(false, List.of(), List.of(), WORKER, C1))
        .as("새 계정에 역할 넣기도 변경")
        .isInstanceOf(PmartException.class);
  }

  @Test
  void switchOnAllowsNonAdminRoles() {
    assertThatCode(
            () ->
                EmployeeAdminService.checkRoleEdit(
                    true, WORKER, C1, List.of("MFC_SUPERVISOR"), List.of("MFC-2")))
        .doesNotThrowAnyException();
  }

  @Test
  void adminRolesAreNeverAddedEvenWithSwitchOn() {
    for (String admin : List.of("OPERATOR_ADMIN", "HR_ADMIN")) {
      assertThatThrownBy(
              () -> EmployeeAdminService.checkRoleEdit(true, WORKER, C1, List.of(admin), C1))
          .as(admin)
          .isInstanceOf(PmartException.class);
    }
    // 이미 있는 관리자 역할을 그대로 두는 상태 변경은 된다
    assertThatCode(
            () ->
                EmployeeAdminService.checkRoleEdit(
                    true, List.of("HR_ADMIN"), List.of(), List.of("HR_ADMIN"), List.of()))
        .doesNotThrowAnyException();
  }
}
