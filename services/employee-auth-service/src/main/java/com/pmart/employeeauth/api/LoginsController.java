package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.LoginsApi;
import com.pmart.employeeauth.application.EmployeeLoginService;
import com.pmart.employeeauth.application.EmployeeSessionService;
import com.pmart.employeeauth.infrastructure.KeycloakClient;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 LoginsApi — 브라우저가 여는 로그인 시작·Keycloak 콜백(둘 다 302 만, 본문 없음)과 Keycloak 이 서버끼리 부르는 백채널
 * 로그아웃(ADR-0061).
 */
@RestController
public class LoginsController implements LoginsApi {

  private static final Logger log = LoggerFactory.getLogger(LoginsController.class);

  private final EmployeeLoginService logins;
  private final EmployeeSessionService sessions;

  public LoginsController(EmployeeLoginService logins, EmployeeSessionService sessions) {
    this.logins = logins;
    this.sessions = sessions;
  }

  @Override
  public ResponseEntity<Void> startEmployeeLogin(
      String app,
      String deviceId,
      String codeChallenge,
      String codeChallengeMethod,
      @Nullable String returnUri,
      @Nullable Boolean silent) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(
            logins.start(
                app,
                deviceId,
                codeChallenge,
                codeChallengeMethod,
                returnUri,
                Boolean.TRUE.equals(silent)))
        .cacheControl(CacheControl.noStore())
        .build();
  }

  @Override
  public ResponseEntity<Void> completeEmployeeLogin(
      @Nullable String state, @Nullable String code, @Nullable String error) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(logins.complete(state, code, error))
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  @Override
  public ResponseEntity<Void> receiveKeycloakLogout(String logoutToken) {
    try {
      sessions.receiveKeycloakLogout(logoutToken);
    } catch (KeycloakClient.Rejected e) {
      // 사유만(토큰 값은 남기지 않는다)
      log.warn("Keycloak 백채널 로그아웃 거절: {}", e.getMessage());
      throw new InvalidLogoutToken();
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
  }

  /** OIDC Back-Channel Logout §2.8 — 무효면 400 {@code {"error":"invalid_request"}}. */
  static final class InvalidLogoutToken extends RuntimeException {
    InvalidLogoutToken() {
      super("invalid logout_token", null, false, false);
    }
  }

  @ExceptionHandler(InvalidLogoutToken.class)
  ResponseEntity<Map<String, String>> invalidLogoutToken() {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("error", "invalid_request"));
  }

  @ExceptionHandler(KeycloakClient.Unavailable.class)
  ResponseEntity<Void> keycloakUnavailable(KeycloakClient.Unavailable e) {
    log.warn("Keycloak 백채널 로그아웃: 공개키 확인 불가 — {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
