package com.pmart.auth.api;

import com.pmart.auth.api.generated.InternalApi;
import com.pmart.auth.api.generated.model.VerifyCustomerReauthentication200Response;
import com.pmart.auth.api.generated.model.VerifyCustomerReauthenticationRequest;
import com.pmart.auth.application.CustomerWithdrawalService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에
 * 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final CustomerWithdrawalService withdrawals;
  private final com.pmart.auth.application.CustomerActivityService activities;

  public InternalController(
      CustomerWithdrawalService withdrawals,
      com.pmart.auth.application.CustomerActivityService activities) {
    this.withdrawals = withdrawals;
    this.activities = activities;
  }

  /** 고객 재인증 확인(탈퇴 접수·철회 전, 0.9.0 — ADR-0077). 상태 변경 없음. */
  @Override
  public ResponseEntity<VerifyCustomerReauthentication200Response> verifyCustomerReauthentication(
      String accountId, VerifyCustomerReauthenticationRequest body) {
    String provider = body.getProvider().getValue();
    withdrawals.verifyReauthentication(accountId, provider, body.getProviderToken());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new VerifyCustomerReauthentication200Response().verified(true).provider(provider));
  }

  /** 0.12.0 CU-6 — 고객 마지막 로그인·토큰 갱신(요청 순서). */
  @Override
  public ResponseEntity<com.pmart.auth.api.generated.model.GetCustomerActivities200Response>
      getCustomerActivities(String accountIds) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new com.pmart.auth.api.generated.model.GetCustomerActivities200Response()
                .items(
                    activities.activities(accountIds).stream()
                        .map(
                            a ->
                                new com.pmart.auth.api.generated.model
                                        .GetCustomerActivities200ResponseItemsInner()
                                    .accountId(a.accountId())
                                    .lastLoginAt(utc(a.lastLoginAt()))
                                    .lastTokenRefreshAt(utc(a.lastTokenRefreshAt())))
                        .toList()));
  }

  private static java.time.@org.jspecify.annotations.Nullable OffsetDateTime utc(
      java.time.@org.jspecify.annotations.Nullable Instant i) {
    return i == null ? null : java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
  }
}
