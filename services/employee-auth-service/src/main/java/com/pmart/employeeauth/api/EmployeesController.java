package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.EmployeesApi;
import com.pmart.employeeauth.api.generated.model.Employee;
import com.pmart.employeeauth.api.generated.model.EmployeeChange;
import com.pmart.employeeauth.api.generated.model.RevokeEmployeeGrantRequest;
import com.pmart.employeeauth.application.EmployeeAdminService;
import com.pmart.employeeauth.infrastructure.EmployeeNames;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 EmployeesApi — 직원 상태·역할·권한 센터 관리(OPERATOR_ADMIN, ADR-0056 5단계 전까지). */
@RestController
public class EmployeesController implements EmployeesApi {

  private final EmployeeAdminService admin;
  private final EmployeeNames names;

  public EmployeesController(EmployeeAdminService admin, EmployeeNames names) {
    this.admin = admin;
    this.names = names;
  }

  /** 승인자·회수자 이름(0.10.0)을 붙여 응답으로 — hr-service 를 한 번 부르고, 못 읽으면 이름 없이. */
  private Employee employee(EmployeeAdminService.View v) {
    List<String> ids = new ArrayList<>();
    v.grants()
        .forEach(
            g -> {
              ids.add(g.approverId());
              if (g.revokedBy() != null) {
                ids.add(g.revokedBy());
              }
            });
    return EmployeeAuthMapping.employee(v, names.names(ids));
  }

  @Override
  public ResponseEntity<Employee> getEmployee(String operatorId) {
    return ResponseEntity.ok(employee(admin.get(operatorId)));
  }

  @Override
  public ResponseEntity<Employee> revokeEmployeeGrant(
      String idempotencyKey,
      String xSourceSystem,
      String operatorId,
      RevokeEmployeeGrantRequest body) {
    return ResponseEntity.ok(
        employee(
            admin.revokeGrant(
                operatorId, body.getGrantId(), body.getReason().strip(), xSourceSystem)));
  }

  @Override
  public ResponseEntity<Employee> putEmployee(
      String idempotencyKey, String xSourceSystem, String operatorId, EmployeeChange body) {
    return ResponseEntity.ok(
        employee(
            admin.view(
                admin.put(
                    operatorId,
                    new EmployeeAdminService.Change(
                        body.getState().getValue(),
                        body.getRoles(),
                        body.getCenters(),
                        body.getReason()),
                    xSourceSystem))));
  }
}
