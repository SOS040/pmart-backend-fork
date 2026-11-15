package com.pmart.hr.api;

import com.pmart.hr.api.generated.model.Affiliation;
import com.pmart.hr.api.generated.model.Employee;
import com.pmart.hr.api.generated.model.EmployeeRegistration;
import com.pmart.hr.api.generated.model.EmployeeSelf;
import com.pmart.hr.api.generated.model.EmploymentStatus;
import com.pmart.hr.api.generated.model.EmploymentType;
import com.pmart.hr.api.generated.model.GrantRequestState;
import com.pmart.hr.api.generated.model.Provisioning;
import com.pmart.hr.api.generated.model.ProvisioningStep;
import com.pmart.hr.api.generated.model.RequestedGrant;
import com.pmart.hr.application.EmployeeService;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import com.pmart.hr.infrastructure.HrStore.ProvisioningRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 요청·응답 변환. */
final class HrMapping {

  private HrMapping() {}

  static EmployeeService.Registration registration(EmployeeRegistration r) {
    List<RequestedGrant> grants =
        r.getRequestedGrants() == null ? List.of() : r.getRequestedGrants();
    return new EmployeeService.Registration(
        r.getName().strip(),
        r.getMailId(),
        r.getEmploymentType().getValue(),
        r.getAffiliation().getType().getValue(),
        r.getAffiliation().getCode(),
        r.getStartDate(),
        r.getEndDate(),
        grants.stream()
            .map(
                g ->
                    new EmployeeService.GrantRequest(
                        g.getRole(),
                        g.getCenters() == null ? List.of() : List.copyOf(g.getCenters()),
                        g.getValidFrom(),
                        g.getValidTo(),
                        g.getReason()))
            .toList());
  }

  static List<EmployeeService.GrantRequest> grants(@Nullable List<RequestedGrant> grants) {
    return (grants == null ? List.<RequestedGrant>of() : grants)
        .stream()
            .map(
                g ->
                    new EmployeeService.GrantRequest(
                        g.getRole(),
                        g.getCenters() == null ? List.of() : List.copyOf(g.getCenters()),
                        g.getValidFrom(),
                        g.getValidTo(),
                        g.getReason()))
            .toList();
  }

  static Employee employee(EmployeeService.EmployeeView v) {
    EmployeeRow e = v.employee();
    ProvisioningRow p = v.provisioning();
    return new Employee()
        .operatorId(e.operatorId())
        .employeeNo(e.employeeNo())
        .name(e.name())
        .mailAddress(e.mailAddress())
        .employmentType(EmploymentType.fromValue(e.employmentType()))
        .affiliation(affiliation(e))
        .startDate(e.startDate())
        .endDate(e.endDate())
        .status(EmploymentStatus.fromValue(e.status()))
        .provisioning(
            new Provisioning()
                .step(ProvisioningStep.fromValue(p.step()))
                .attempts(p.attempts())
                .lastErrorCode(p.lastErrorCode())
                .nextAttemptAt(odt(p.nextAttemptAt()))
                .updatedAt(odt(p.updatedAt())))
        .requestedGrants(
            v.grants().stream()
                .map(
                    g ->
                        new GrantRequestState()
                            .role(g.role())
                            .centers(g.centers())
                            // 비워 받은 기간 = 근무 기간(GrantRequested 와 같은 값, 2026-11-13 클라이언트 회신)
                            .validFrom(g.validFrom() != null ? g.validFrom() : e.startDate())
                            .validTo(g.validTo() != null ? g.validTo() : e.endDate())
                            .reason(g.reason())
                            .approvalRef(g.approvalRef())
                            .submission(GrantRequestState.SubmissionEnum.fromValue(g.submission())))
                .toList())
        .version(e.version())
        .createdAt(odt(e.createdAt()))
        .updatedAt(odt(e.updatedAt()));
  }

  static EmployeeSelf self(EmployeeRow e) {
    return new EmployeeSelf()
        .operatorId(e.operatorId())
        .employeeNo(e.employeeNo())
        .name(e.name())
        .mailAddress(e.mailAddress())
        .employmentType(EmploymentType.fromValue(e.employmentType()))
        .affiliation(affiliation(e))
        .status(EmploymentStatus.fromValue(e.status()));
  }

  private static Affiliation affiliation(EmployeeRow e) {
    return new Affiliation()
        .type(Affiliation.TypeEnum.fromValue(e.affiliationType()))
        .code(e.affiliationCode());
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
