package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.RolesApi;
import com.pmart.employeeauth.api.generated.model.EmployeeRole;
import com.pmart.employeeauth.api.generated.model.EmployeeRoleList;
import com.pmart.employeeauth.application.EmployeeRoleCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 RolesApi — 직원 역할 목록(0.5.0, BR-57). */
@RestController
public class RolesController implements RolesApi {

  private final EmployeeRoleCatalog catalog;

  public RolesController(EmployeeRoleCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public ResponseEntity<EmployeeRoleList> listEmployeeRoles() {
    return ResponseEntity.ok(
        new EmployeeRoleList()
            .items(
                catalog.list().stream()
                    .map(
                        r ->
                            new EmployeeRole()
                                .code(r.code())
                                .name(r.name())
                                .centerScoped(r.centerScoped())
                                .order(r.order()))
                    .toList()));
  }
}
