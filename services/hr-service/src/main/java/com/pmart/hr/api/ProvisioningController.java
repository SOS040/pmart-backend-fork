package com.pmart.hr.api;

import com.pmart.hr.api.generated.ProvisioningApi;
import com.pmart.hr.api.generated.model.Employee;
import com.pmart.hr.api.generated.model.ResetMailboxPassword200Response;
import com.pmart.hr.application.EmployeeService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ProvisioningApi — 안내 메일 다시 보내기, 실패한 발급 다시 시도, 웹메일 초기 비밀번호 발급. 비밀번호 발급은 결과를 재생하지 않는다(저장하지 않으려고
 * — 같은 키로 다시 보내도 새로 발급).
 */
@RestController
public class ProvisioningController implements ProvisioningApi {

  private final EmployeeService employees;

  public ProvisioningController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<Void> resendInvite(
      String operatorId, String idempotencyKey, String xSourceSystem) {
    employees.resendInvite(operatorId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  @Override
  public ResponseEntity<Employee> retryProvisioning(
      String operatorId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(HrMapping.employee(employees.retryProvisioning(operatorId)));
  }

  @Override
  public ResponseEntity<ResetMailboxPassword200Response> resetMailboxPassword(
      String operatorId, String idempotencyKey, String xSourceSystem) {
    String password = employees.resetMailboxPassword(operatorId);
    String address = employees.get(operatorId).employee().mailAddress();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new ResetMailboxPassword200Response().mailAddress(address).initialPassword(password));
  }
}
