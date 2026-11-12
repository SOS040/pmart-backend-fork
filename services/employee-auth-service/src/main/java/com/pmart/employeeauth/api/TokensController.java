package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.TokensApi;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.EmployeeLogoutRequest;
import com.pmart.employeeauth.api.generated.model.EmployeeLogoutResult;
import com.pmart.employeeauth.api.generated.model.EmployeeSession;
import com.pmart.employeeauth.api.generated.model.ExchangeLoginTicketRequest;
import com.pmart.employeeauth.api.generated.model.RefreshEmployeeTokensRequest;
import com.pmart.employeeauth.api.generated.model.TokenPair;
import com.pmart.employeeauth.application.EmployeeLoginService;
import com.pmart.employeeauth.application.EmployeeSessionService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 TokensApi — 티켓 교환·갱신·로그아웃·현재 세션. 티켓·refresh token 은 한 번만 쓰므로 같은 Idempotency-Key 재전송도 결과를 재생하지
 * 않는다(토큰을 저장하지 않으려고).
 */
@RestController
public class TokensController implements TokensApi {

  private final EmployeeLoginService logins;
  private final EmployeeSessionService sessions;

  public TokensController(EmployeeLoginService logins, EmployeeSessionService sessions) {
    this.logins = logins;
    this.sessions = sessions;
  }

  @Override
  public ResponseEntity<EmployeeLoginResult> exchangeLoginTicket(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      ExchangeLoginTicketRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            EmployeeAuthMapping.login(
                logins.exchangeTicket(
                    body.getTicket(), body.getCodeVerifier(), xDeviceId, xSourceSystem)));
  }

  @Override
  public ResponseEntity<TokenPair> refreshEmployeeTokens(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      RefreshEmployeeTokensRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(EmployeeAuthMapping.tokens(sessions.refresh(body.getRefreshToken(), xDeviceId)));
  }

  @Override
  public ResponseEntity<EmployeeLogoutResult> employeeLogout(
      String idempotencyKey, String xSourceSystem, @Nullable EmployeeLogoutRequest body) {
    java.net.URI idp = sessions.logout(body != null && Boolean.TRUE.equals(body.getAllDevices()));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new EmployeeLogoutResult().idpLogoutUri(idp == null ? null : idp.toString()));
  }

  @Override
  public ResponseEntity<EmployeeSession> getCurrentEmployeeSession() {
    EmployeeSessionService.Current c = sessions.current();
    return ResponseEntity.ok(
        new EmployeeSession()
            .sessionId(c.sessionId())
            .operatorId(c.operatorId())
            .app(c.app())
            .issuedAt(EmployeeAuthMapping.odt(c.issuedAt()))
            .expiresAt(EmployeeAuthMapping.odt(c.expiresAt())));
  }
}
