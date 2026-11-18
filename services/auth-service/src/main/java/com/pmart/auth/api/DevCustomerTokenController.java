package com.pmart.auth.api;

import com.pmart.auth.api.generated.DevApi;
import com.pmart.auth.api.generated.model.IssueDevCustomerToken200Response;
import com.pmart.auth.api.generated.model.IssueDevCustomerTokenRequest;
import com.pmart.auth.application.DevCustomerTokenService;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 DevApi — [LAN 시험 전용] 개발용 고객 토큰(0.3.0, MFC 시뮬레이터 R-21). {@code
 * pmart.auth.dev-customer-token.enabled=true} 일 때만 빈이 생긴다(없으면 경로 자체가 없어 404).
 */
@RestController
@ConditionalOnProperty(
    prefix = "pmart.auth.dev-customer-token",
    name = "enabled",
    havingValue = "true")
public class DevCustomerTokenController implements DevApi {

  private final DevCustomerTokenService service;

  public DevCustomerTokenController(DevCustomerTokenService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<IssueDevCustomerToken200Response> issueDevCustomerToken(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      IssueDevCustomerTokenRequest body) {
    DevCustomerTokenService.Issued t = service.issue(body.getAccountId(), xDeviceId, xSourceSystem);
    return ResponseEntity.ok(
        new IssueDevCustomerToken200Response()
            .accountId(t.accountId())
            .accessToken(t.accessToken())
            .accessTokenExpiresAt(t.expiresAt().atOffset(ZoneOffset.UTC))
            .sessionId(t.sessionId()));
  }
}
