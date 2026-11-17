package com.pmart.auth.api;

import com.pmart.auth.api.generated.LoginsApi;
import com.pmart.auth.api.generated.model.AuthResult;
import com.pmart.auth.api.generated.model.AuthState;
import com.pmart.auth.api.generated.model.CompleteSocialLoginRequest;
import com.pmart.auth.api.generated.model.SocialLoginRequest;
import com.pmart.auth.api.generated.model.StartGuestSession201Response;
import com.pmart.auth.application.ConsentDecision;
import com.pmart.auth.application.CustomerSocialLoginService;
import com.pmart.auth.application.GuestSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LoginsApi — 고객 소셜 로그인(지금은 GOOGLE)·비회원 세션. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class LoginsController implements LoginsApi {

  private final CustomerSocialLoginService logins;
  private final GuestSessionService guests;

  public LoginsController(CustomerSocialLoginService logins, GuestSessionService guests) {
    this.logins = logins;
    this.guests = guests;
  }

  @Override
  public ResponseEntity<AuthResult> socialLogin(
      String idempotencyKey, String xSourceSystem, String xDeviceId, SocialLoginRequest body) {
    CustomerSocialLoginService.Result r =
        logins.login(
            idempotencyKey,
            xSourceSystem,
            xDeviceId,
            body.getProvider().getValue(),
            body.getProviderToken());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result(r));
  }

  @Override
  public ResponseEntity<StartGuestSession201Response> startGuestSession(
      String idempotencyKey, String xSourceSystem, String xDeviceId) {
    GuestSessionService.Started s = guests.start(idempotencyKey, xSourceSystem, xDeviceId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(
            new StartGuestSession201Response()
                .guestId(s.guestId())
                .tokens(AuthMapping.tokens(s.tokens())));
  }

  @Override
  public ResponseEntity<AuthResult> getSocialLogin(String authRequestId, String xDeviceId) {
    return ResponseEntity.ok(result(logins.result(authRequestId, xDeviceId)));
  }

  @Override
  public ResponseEntity<AuthResult> completeSocialLogin(
      String authRequestId,
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      CompleteSocialLoginRequest body) {
    // 필수 동의 완료(AUTH_CONSENT → AUTH_MEMBER, 0.2.0 — CU-1·BR-20). 토큰이 없는 단계라 Google ID 토큰을 다시 받아 본인을
    // 확인한다
    CustomerSocialLoginService.Result r =
        logins.complete(
            authRequestId,
            idempotencyKey,
            xSourceSystem,
            xDeviceId,
            body.getProviderToken(),
            body.getConsents().stream()
                .map(
                    c ->
                        new ConsentDecision(
                            c.getConsentType().strip(),
                            c.getVersion().strip(),
                            Boolean.TRUE.equals(c.getAgreed())))
                .toList());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result(r));
  }

  private static AuthResult result(CustomerSocialLoginService.Result r) {
    boolean consent = AuthState.AUTH_CONSENT.getValue().equals(r.state());
    return new AuthResult()
        .authRequestId(r.authRequestId())
        .state(AuthState.fromValue(r.state()))
        .accountId(r.accountId())
        .newAccount(r.newAccount())
        .tokens(r.tokens() == null ? null : AuthMapping.tokens(r.tokens()))
        .requiredConsents(consent ? r.requiredConsents() : null)
        .optionalConsents(consent ? r.optionalConsents() : null)
        .nextAction(AuthResult.NextActionEnum.fromValue(r.nextAction()))
        .occurredAt(AuthMapping.odt(r.occurredAt()));
  }
}
