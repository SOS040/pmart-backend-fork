package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.DevLocalApi;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.MintDevTokenRequest;
import com.pmart.employeeauth.application.EmployeeLoginService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 DevLocalApi — local 프로필 개발 전용 직원 토큰 발급(2026-11-13, Keycloak 없음). {@code
 * pmart.employee-auth.dev-token-mint.enabled=true} 일 때만 빈이 생긴다(없으면 경로가 없어 404). LAN·운영 설정에는 두지 않는다.
 */
@RestController
@ConditionalOnProperty(
    prefix = "pmart.employee-auth.dev-token-mint",
    name = "enabled",
    havingValue = "true")
public class DevMintController implements DevLocalApi {

  private final EmployeeLoginService logins;

  public DevMintController(EmployeeLoginService logins) {
    this.logins = logins;
  }

  @Override
  public ResponseEntity<EmployeeLoginResult> mintDevToken(
      String idempotencyKey, String xSourceSystem, String xDeviceId, MintDevTokenRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            EmployeeAuthMapping.login(
                logins.mintDevToken(
                    body.getOperatorId(),
                    body.getRoles(),
                    body.getCenters() == null ? List.of() : body.getCenters(),
                    xDeviceId)));
  }
}
