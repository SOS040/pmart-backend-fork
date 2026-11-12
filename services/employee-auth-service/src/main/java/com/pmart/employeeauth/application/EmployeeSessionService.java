package com.pmart.employeeauth.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.RefreshRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.SessionRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토큰 갱신·로그아웃·현재 세션. refresh token 은 한 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션 폐기(TOKEN_REUSED). 직원 상태·역할은 갱신
 * 때마다 다시 읽는다(권한이 바뀌면 다음 갱신부터 반영). 세션 만료 = min(갱신 시각 + 앱 무활동 한도, 최대 만료).
 */
@Service
public class EmployeeSessionService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeSessionService.class);

  public record Current(
      String sessionId, String operatorId, String app, Instant issuedAt, Instant expiresAt) {}

  private final EmployeeAuthStore store;
  private final EmployeeTokens tokens;
  private final EmployeeAuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public EmployeeSessionService(
      EmployeeAuthStore store,
      EmployeeTokens tokens,
      EmployeeAuthProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.tokens = tokens;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public EmployeeTokens.Tokens refresh(String refreshToken, @Nullable String deviceId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String[] reason = new String[1];
    EmployeeTokens.Tokens result =
        tx.execute(
            status -> {
              RefreshRow r = store.lockRefresh(EmployeeTokens.hash(refreshToken)).orElse(null);
              if (r == null) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionRow s = store.session(r.sessionId(), true).orElseThrow();
              if (r.usedAt() != null) {
                store.revokeSession(s.sessionId(), "REFRESH_REUSED", now);
                log.warn("refresh token 재사용 감지: sessionId={}", s.sessionId());
                reason[0] = "TOKEN_REUSED";
                return null;
              }
              if (!"ACTIVE".equals(s.state())
                  || !r.expiresAt().isAfter(now)
                  || !s.expiresAt().isAfter(now)
                  || !s.deviceId().equals(deviceId)) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              AccountRow account = store.account(s.operatorId(), false).orElse(null);
              if (account == null || !EmployeeLoginService.ACTIVE.equals(account.state())) {
                store.revokeSession(s.sessionId(), "ACCOUNT_DISABLED", now);
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              Instant idle = now.plus(props.sessionIdle(s.app()));
              Instant extended = idle.isBefore(s.maxExpiresAt()) ? idle : s.maxExpiresAt();
              store.markRefreshUsed(r.tokenHash(), now);
              store.refreshed(s.sessionId(), now, extended);
              return tokens.issue(
                  account.operatorId(),
                  account.roles(),
                  account.centers(),
                  s.sessionId(),
                  extended,
                  now);
            });
    if (result == null) {
      throw new PmartException(
          reason[0],
          HttpStatus.UNAUTHORIZED,
          "TOKEN_REUSED".equals(reason[0])
              ? "이미 사용한 refresh token 입니다. 세션을 종료했으니 다시 로그인하세요."
              : "refresh token 이 유효하지 않습니다. 다시 로그인하세요.",
          false,
          List.of());
    }
    return result;
  }

  /** 현재 토큰의 세션(또는 그 직원의 모든 세션) 종료. 이미 종료여도 성공. */
  public void logout(boolean allDevices) {
    Jwt jwt = ownJwt();
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          if (allDevices) {
            store.revokeAllSessions(jwt.getSubject(), "LOGOUT_ALL", now);
          } else if (jwt.getClaimAsString("sid") != null) {
            store.revokeSession(jwt.getClaimAsString("sid"), "LOGOUT", now);
          }
        });
  }

  public Current current() {
    Jwt jwt = ownJwt();
    String sid = jwt.getClaimAsString("sid");
    SessionRow s = sid == null ? null : store.session(sid, false).orElse(null);
    if (s == null
        || !"ACTIVE".equals(s.state())
        || !s.expiresAt().isAfter(clock.instant())
        || !s.operatorId().equals(jwt.getSubject())) {
      throw new PmartException(
          "SESSION_EXPIRED", HttpStatus.UNAUTHORIZED, "세션이 종료되었습니다. 다시 로그인하세요.", false, List.of());
    }
    return new Current(
        s.sessionId(),
        s.operatorId(),
        s.app(),
        Objects.requireNonNull(jwt.getIssuedAt()),
        Objects.requireNonNull(jwt.getExpiresAt()));
  }

  /** 이 서비스가 발급한 직원 토큰만(Keycloak 토큰·다른 발급자는 403). */
  private Jwt ownJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof Jwt jwt
        && EmployeeTokens.TYPE.equals(jwt.getClaimAsString("typ"))
        && jwt.getIssuer() != null
        && props.issuer().equals(jwt.getIssuer().toString())) {
      return jwt;
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "employee-auth-service 가 발급한 직원 토큰이 필요합니다.");
  }
}
