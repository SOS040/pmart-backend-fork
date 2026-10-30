package com.pmart.auth.api;

import com.pmart.auth.api.generated.TokensApi;
import com.pmart.auth.api.generated.model.LogoutRequest;
import com.pmart.auth.api.generated.model.RefreshTokensRequest;
import com.pmart.auth.api.generated.model.Session;
import com.pmart.auth.api.generated.model.TokenPair;
import com.pmart.auth.application.SessionService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 TokensApi — 토큰 갱신·로그아웃·현재 세션(운영자·고객). refresh token 은 회전이라 같은 Idempotency-Key 로 재전송해도 결과를 재생하지
 * 않는다(토큰을 저장하지 않기 위해) — 쓴 토큰을 다시 보내면 TOKEN_REUSED.
 */
@RestController
public class TokensController implements TokensApi {

  private final SessionService sessions;

  public TokensController(SessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public ResponseEntity<TokenPair> refreshTokens(
      String idempotencyKey, String xSourceSystem, String xDeviceId, RefreshTokensRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AuthMapping.tokens(sessions.refresh(body.getRefreshToken(), xDeviceId)));
  }

  @Override
  public ResponseEntity<Void> logout(
      String idempotencyKey, String xSourceSystem, @Nullable LogoutRequest body) {
    sessions.logout(body != null && Boolean.TRUE.equals(body.getAllDevices()));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Session> getCurrentSession() {
    SessionService.Current c = sessions.current();
    return ResponseEntity.ok(
        new Session()
            .sessionId(c.sessionId())
            .principalId(c.principalId())
            .principalType(Session.PrincipalTypeEnum.fromValue(c.principalType()))
            .accountId(c.principalId())
            .roles(c.roles())
            .centerScope(c.centers())
            .activeDelegationIds(List.of())
            .issuedAt(AuthMapping.odt(c.issuedAt()))
            .expiresAt(AuthMapping.odt(c.expiresAt())));
  }
}
