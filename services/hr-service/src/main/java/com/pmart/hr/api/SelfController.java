package com.pmart.hr.api;

import com.pmart.hr.api.generated.SelfApi;
import com.pmart.hr.api.generated.model.EmployeeSelf;
import com.pmart.hr.application.EmployeeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SelfApi — 본인 인사 정보(모든 직원). */
@RestController
public class SelfController implements SelfApi {

  private final EmployeeService employees;

  public SelfController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<EmployeeSelf> getMyEmployee() {
    return ResponseEntity.ok(HrMapping.self(employees.me()));
  }
}
