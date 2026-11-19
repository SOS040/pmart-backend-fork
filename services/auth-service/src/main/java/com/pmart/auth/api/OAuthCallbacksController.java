package com.pmart.auth.api;

import com.pmart.auth.api.generated.OauthCallbacksApi;
import com.pmart.auth.domain.OAuthCallbackRules;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 계약 OauthCallbacksApi(0.5.0, 2026-11-19 BR-66) — 네이버 로그인 콜백을 앱 주소로 302. 쿼리 값(인가 코드)은 읽지 않고 원문 그대로
 * 넘긴다 — 로그·저장 없음.
 */
@RestController
public class OAuthCallbacksController implements OauthCallbacksApi {

  @Override
  public ResponseEntity<Void> naverCallbackCustomer(
      String code, String state, String error, String errorDescription) {
    return relay(OAuthCallbackRules.CUSTOMER_NAVER);
  }

  @Override
  public ResponseEntity<Void> naverCallbackRider(
      String code, String state, String error, String errorDescription) {
    return relay(OAuthCallbackRules.RIDER_NAVER);
  }

  private static ResponseEntity<Void> relay(String target) {
    HttpServletRequest req =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    String location = OAuthCallbackRules.location(target, req.getQueryString());
    if (location == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "콜백 쿼리가 너무 깁니다.");
    }
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(location))
        .cacheControl(CacheControl.noStore())
        .build();
  }
}
