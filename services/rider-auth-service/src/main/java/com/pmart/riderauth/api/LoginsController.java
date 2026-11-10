package com.pmart.riderauth.api;

import com.pmart.riderauth.api.generated.LoginsApi;
import com.pmart.riderauth.api.generated.model.RiderAuthResult;
import com.pmart.riderauth.api.generated.model.RiderSocialLoginRequest;
import com.pmart.riderauth.application.RiderLoginService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LoginsApi — 라이더 Google 로그인. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class LoginsController implements LoginsApi {

  private final RiderLoginService logins;

  public LoginsController(RiderLoginService logins) {
    this.logins = logins;
  }

  @Override
  public ResponseEntity<RiderAuthResult> riderSocialLogin(
      String idempotencyKey, String xSourceSystem, String xDeviceId, RiderSocialLoginRequest body) {
    RiderLoginService.Result r =
        logins.login(
            idempotencyKey,
            xSourceSystem,
            xDeviceId,
            body.getProvider().getValue(),
            body.getProviderToken());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(RiderAuthMapping.result(r));
  }

  @Override
  public ResponseEntity<RiderAuthResult> getRiderSocialLogin(
      String authRequestId, String xDeviceId) {
    return ResponseEntity.ok(RiderAuthMapping.result(logins.result(authRequestId, xDeviceId)));
  }
}
