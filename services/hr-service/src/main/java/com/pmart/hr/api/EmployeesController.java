package com.pmart.hr.api;

import com.pmart.hr.api.generated.EmployeesApi;
import com.pmart.hr.api.generated.model.ChangeAffiliationRequest;
import com.pmart.hr.api.generated.model.CountValue;
import com.pmart.hr.api.generated.model.Employee;
import com.pmart.hr.api.generated.model.EmployeeCounts;
import com.pmart.hr.api.generated.model.EmployeePage;
import com.pmart.hr.api.generated.model.EmployeeRegistration;
import com.pmart.hr.api.generated.model.EmploymentStatus;
import com.pmart.hr.application.EmployeeService;
import com.pmart.hr.infrastructure.HrStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
      @Nullable String provisioningStep,
      @Nullable String grantSubmission,
      @Nullable LocalDate endDateFrom,
      @Nullable LocalDate endDateTo,
      @Nullable OffsetDateTime createdFrom,
      @Nullable OffsetDateTime createdTo,
      String sort,
      Integer size,
      @Nullable String cursor) {
    EmployeeService.Page page =
        employees.list(
            new HrStore.ListFilter(
                status == null ? null : status.getValue(),
                affiliationCode,
                q,
                List.of(),
                grantSubmission,
                endDateFrom,
                endDateTo,
                createdFrom == null ? null : createdFrom.toInstant(),
                createdTo == null ? null : createdTo.toInstant(),
                "END_DATE_ASC".equals(sort)),
            provisioningStep,
            cursor,
            size == null ? 50 : size);
    EmployeePage body =
        new EmployeePage().items(page.items().stream().map(HrMapping::employee).toList());
    if (page.nextCursor() != null) {
      body.nextCursor(page.nextCursor());
    }
    return ResponseEntity.ok(body);
  }

  /** 인원 세기(0.9.0 H-D2). */
  @Override
  public ResponseEntity<EmployeeCounts> countEmployees(@Nullable Integer endingWithinDays) {
    EmployeeService.Counts c = employees.counts(endingWithinDays);
    return ResponseEntity.ok(
        new EmployeeCounts()
            .asOf(OffsetDateTime.ofInstant(c.asOf(), ZoneOffset.UTC))
            .active(count(c.active()))
            .suspended(count(c.suspended()))
            .left(count(c.left()))
            .provisioningFailed(count(c.provisioningFailed()))
            .grantPendingApproval(count(c.grantPendingApproval()))
            .endingSoon(count(c.endingSoon()))
            .endingWithinDays(c.endingWithinDays())
            .endingFrom(c.endingFrom())
            .endingTo(c.endingTo()));
  }

  private static CountValue count(long n) {
    return new CountValue().value(Math.toIntExact(n)).complete(true);
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
