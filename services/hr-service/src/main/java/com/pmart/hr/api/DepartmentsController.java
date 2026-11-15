package com.pmart.hr.api;

import com.pmart.hr.api.generated.DepartmentsApi;
import com.pmart.hr.api.generated.model.Department;
import com.pmart.hr.api.generated.model.DepartmentList;
import com.pmart.hr.api.generated.model.PutDepartmentRequest;
import com.pmart.hr.application.DepartmentService;
import com.pmart.hr.infrastructure.HrStore.DepartmentRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DepartmentsApi — 본사 부서 코드 목록·표시 이름. */
@RestController
public class DepartmentsController implements DepartmentsApi {

  private final DepartmentService departments;

  public DepartmentsController(DepartmentService departments) {
    this.departments = departments;
  }

  @Override
  public ResponseEntity<DepartmentList> listDepartments() {
    return ResponseEntity.ok(
        new DepartmentList()
            .items(departments.list().stream().map(DepartmentsController::department).toList()));
  }

  @Override
  public ResponseEntity<Department> putDepartment(
      String code, String idempotencyKey, String xSourceSystem, PutDepartmentRequest body) {
    return ResponseEntity.ok(
        department(departments.rename(idempotencyKey, xSourceSystem, code, body.getName())));
  }

  private static Department department(DepartmentRow d) {
    return new Department().code(d.code()).name(d.name()).employeeCount(d.employeeCount());
  }
}
