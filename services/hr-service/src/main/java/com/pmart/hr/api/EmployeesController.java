package com.pmart.hr.api;

import com.pmart.hr.api.generated.EmployeesApi;
import com.pmart.hr.api.generated.model.ChangeAffiliationRequest;
import com.pmart.hr.api.generated.model.Employee;
import com.pmart.hr.api.generated.model.EmployeePage;
import com.pmart.hr.api.generated.model.EmployeeRegistration;
import com.pmart.hr.api.generated.model.EmploymentStatus;
import com.pmart.hr.application.EmployeeService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 EmployeesApi — 직원 등록·조회·소속 변경. 업무 규칙은 application. */
@RestController
public class EmployeesController implements EmployeesApi {

  private final EmployeeService employees;

  public EmployeesController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<Employee> registerEmployee(
      String idempotencyKey, String xSourceSystem, EmployeeRegistration body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            HrMapping.employee(
                employees.register(idempotencyKey, xSourceSystem, HrMapping.registration(body))));
  }

  @Override
  public ResponseEntity<EmployeePage> listEmployees(
      @Nullable EmploymentStatus status,
      @Nullable String affiliationCode,
      @Nullable String q,
      Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<EmployeeService.EmployeeView> page =
        employees.list(status == null ? null : status.getValue(), affiliationCode, q, cursor, n);
    EmployeePage body = new EmployeePage().items(page.stream().map(HrMapping::employee).toList());
    if (page.size() == n) {
      body.nextCursor(page.getLast().employee().employeeNo());
    }
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<Employee> getEmployee(String operatorId) {
    return ResponseEntity.ok(HrMapping.employee(employees.get(operatorId)));
  }

  @Override
  public ResponseEntity<Employee> changeAffiliation(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeAffiliationRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        HrMapping.employee(
            employees.changeAffiliation(
                idempotencyKey,
                xSourceSystem,
                operatorId,
                xExpectedVersion == null ? null : xExpectedVersion.longValue(),
                body.getAffiliation().getType().getValue(),
                body.getAffiliation().getCode(),
                body.getReason())));
  }
}
