package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.DevApi;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.ExchangeDevTokenRequest;
import com.pmart.employeeauth.application.EmployeeLoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 DevApi — LAN 시험 전용 Keycloak 토큰 교환(ADR-0056 D5). {@code
 * pmart.employee-auth.dev-token-exchange.enabled=true} 일 때만 빈이 생긴다(없으면 경로 자체가 없어 404). 운영 설정에는 두지
 * 않는다.
 */
@RestController
@ConditionalOnProperty(
    prefix = "pmart.employee-auth.dev-token-exchange",
    name = "enabled",
    havingValue = "true")
public class DevTokenController implements DevApi {

  private final EmployeeLoginService logins;

  public DevTokenController(EmployeeLoginService logins) {
    this.logins = logins;
  }

  @Override
  public ResponseEntity<EmployeeLoginResult> exchangeDevToken(
      String idempotencyKey, String xSourceSystem, String xDeviceId, ExchangeDevTokenRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            EmployeeAuthMapping.login(
                logins.exchangeDevToken(body.getKeycloakAccessToken(), xDeviceId)));
  }
}
