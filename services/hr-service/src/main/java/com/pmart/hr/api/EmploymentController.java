package com.pmart.hr.api;

import com.pmart.hr.api.generated.EmploymentApi;
import com.pmart.hr.api.generated.model.Employee;
import com.pmart.hr.api.generated.model.ReinstateEmployeeRequest;
import com.pmart.hr.api.generated.model.SuspendEmployeeRequest;
import com.pmart.hr.application.EmployeeService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 EmploymentApi — 정지·복귀·퇴사(ADR-0056 결정 8). */
@RestController
public class EmploymentController implements EmploymentApi {

  private final EmployeeService employees;

  public EmploymentController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<Employee> suspendEmployee(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      SuspendEmployeeRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        HrMapping.employee(
            employees.suspend(
                idempotencyKey,
                xSourceSystem,
                operatorId,
                version(xExpectedVersion),
                body.getReason())));
  }

  @Override
  public ResponseEntity<Employee> reinstateEmployee(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      ReinstateEmployeeRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        HrMapping.employee(
            employees.reinstate(
                idempotencyKey,
                xSourceSystem,
                operatorId,
                version(xExpectedVersion),
                body.getReason(),
                body.getStartDate(),
                body.getEndDate())));
  }

  @Override
  public ResponseEntity<Employee> departEmployee(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      SuspendEmployeeRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        HrMapping.employee(
            employees.depart(
                idempotencyKey,
                xSourceSystem,
                operatorId,
                version(xExpectedVersion),
                body.getReason())));
  }

  private static @Nullable Long version(@Nullable Integer v) {
    return v == null ? null : v.longValue();
  }
}
