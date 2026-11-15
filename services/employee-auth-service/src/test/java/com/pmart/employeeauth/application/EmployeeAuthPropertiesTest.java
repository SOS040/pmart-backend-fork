package com.pmart.employeeauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.employeeauth.application.EmployeeAuthProperties.RoleInfo;
import com.pmart.employeeauth.application.EmployeeAuthProperties.RoleView;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmployeeAuthPropertiesTest {

  static EmployeeAuthProperties props(List<String> roles, List<RoleInfo> info) {
    return new EmployeeAuthProperties(
        "http://ea.test", null, null, null, null, null, null, null, roles, info, null, null, null);
  }

  @Test
  void roleCatalogFollowsRolesOrderAndFallsBackToCode() {
    var p =
        props(
            List.of("MD", "MFC_WORKER", "NEW_ROLE"),
            List.of(
                new RoleInfo("MFC_WORKER", "센터 작업자", true),
                new RoleInfo("MD", "MD(상품 담당)", false),
                new RoleInfo("NOT_IN_ROLES", "무시", true)));
    assertThat(p.roleCatalog())
        .containsExactly(
            new RoleView("MD", "MD(상품 담당)", false, 1),
            new RoleView("MFC_WORKER", "센터 작업자", true, 2),
            new RoleView("NEW_ROLE", "NEW_ROLE", false, 3));
  }

  @Test
  void roleInfoIsOptional() {
    assertThat(props(List.of("MD"), null).roleCatalog())
        .containsExactly(new RoleView("MD", "MD", false, 1));
  }
}
