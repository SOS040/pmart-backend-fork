package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.EmployeesApi;
import com.pmart.employeeauth.api.generated.model.Employee;
import com.pmart.employeeauth.api.generated.model.EmployeeChange;
import com.pmart.employeeauth.api.generated.model.RevokeEmployeeGrantRequest;
import com.pmart.employeeauth.application.EmployeeAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 EmployeesApi — 직원 상태·역할·권한 센터 관리(OPERATOR_ADMIN, ADR-0056 5단계 전까지). */
@RestController
public class EmployeesController implements EmployeesApi {

  private final EmployeeAdminService admin;

  public EmployeesController(EmployeeAdminService admin) {
    this.admin = admin;
  }

  @Override
  public ResponseEntity<Employee> getEmployee(String operatorId) {
    return ResponseEntity.ok(EmployeeAuthMapping.employee(admin.get(operatorId)));
  }

  @Override
  public ResponseEntity<Employee> revokeEmployeeGrant(
      String idempotencyKey,
      String xSourceSystem,
      String operatorId,
      RevokeEmployeeGrantRequest body) {
    return ResponseEntity.ok(
        EmployeeAuthMapping.employee(
            admin.revokeGrant(
                operatorId, body.getGrantId(), body.getReason().strip(), xSourceSystem)));
  }

  @Override
  public ResponseEntity<Employee> putEmployee(
      String idempotencyKey, String xSourceSystem, String operatorId, EmployeeChange body) {
    return ResponseEntity.ok(
        EmployeeAuthMapping.employee(
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
