package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.SessionStore;
import com.pmart.auth.infrastructure.SessionStore.RefreshRow;
import com.pmart.auth.infrastructure.SessionStore.SessionRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
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
 * 토큰 갱신·로그아웃·현재 세션(고객·비회원 — 운영자는 2026-11-13 employee-auth-service 로, ADR-0056). refresh token 은 한
 * 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션을 폐기한다(TOKEN_REUSED). 역할·센터·계정 상태는 갱신 때마다 정본에서 다시 읽는다.
 */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  /** 현재 세션의 주체. */
  public record Current(
      String sessionId,
      String principalId,
      String principalType,
      List<String> roles,
      List<String> centers,
      Instant issuedAt,
      Instant expiresAt) {}

  private final SessionStore store;
  private final CustomerStore customers;
  private final SessionTokens tokens;
  private final CustomerProperties customerProps;
  private final GuestProperties guestProps;
  private final TransactionTemplate tx;
  private final SessionBlocks blocks;
  private final Clock clock;

  public SessionService(
      SessionStore store,
      CustomerStore customers,
      SessionTokens tokens,
      CustomerProperties customerProps,
      GuestProperties guestProps,
      TransactionTemplate tx,
      Clock clock,
      SessionBlocks blocks) {
    this.store = store;
    this.customers = customers;
    this.tokens = tokens;
    this.customerProps = customerProps;
    this.guestProps = guestProps;
    this.tx = tx;
    this.blocks = blocks;
    this.clock = clock;
  }

  public SessionTokens.Tokens refresh(String refreshToken, @Nullable String deviceId) {
    Instant now = clock.instant();
    String[] reason = new String[1];
    SessionTokens.Tokens result =
        tx.execute(
            status -> {
              RefreshRow r = store.lockRefresh(SessionTokens.hash(refreshToken)).orElse(null);
              if (r == null) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionRow s = store.session(r.sessionId(), true).orElseThrow();
              if (r.usedAt() != null) {
                // 이미 쓴 refresh token 이 다시 왔다 — 탈취 가능성, 세션 전체 폐기
                store.revokeSession(s.sessionId(), "REFRESH_REUSED", now);
                // 그 세션의 access token 도 게이트웨이에서 바로 막는다(2026-11-13)
                blocks.block(
                    s.principalId(),
                    s.sessionId(),
                    s.principalType(),
                    now,
                    "TOKEN_REUSED",
                    new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "auth-service"));
                log.warn("refresh token 재사용 감지: sessionId={}", s.sessionId());
                reason[0] = "TOKEN_REUSED";
                return null;
              }
              if (!"ACTIVE".equals(s.state())
                  || !r.expiresAt().isAfter(now)
                  || !s.expiresAt().isAfter(now)
                  || (s.deviceId() != null && !s.deviceId().equals(deviceId))) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionTokens.Tokens issued = reissue(s, now);
              if (issued == null) {
                store.revokeSession(s.sessionId(), "ACCOUNT_DISABLED", now);
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              store.markRefreshUsed(r.tokenHash(), now);
              store.markRefreshed(s.sessionId(), now);
              return issued;
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

  /** 주체 유형별로 정본 상태를 다시 읽어 토큰을 발급한다. 이용할 수 없으면 null. */
  private SessionTokens.@Nullable Tokens reissue(SessionRow s, Instant now) {
    return switch (s.principalType()) {
      case CustomerSocialLoginService.TYPE -> {
        if (!"ACTIVE".equals(customers.accountState(s.principalId()).orElse(null))) {
          yield null;
        }
        // 고객 세션은 마지막 사용 기준으로 연장한다(사용자 결정 2026-11-02): 갱신할 때마다 만료 = 지금 + session-max-age.
        // 그래서 session-max-age 동안 앱을 쓰지 않아야 다시 로그인한다.
        Instant extended = now.plus(customerProps.sessionMaxAge());
        store.extendSession(s.sessionId(), extended);
        yield tokens.issue(
            new SessionTokens.Principal(
                s.principalId(), CustomerSocialLoginService.TYPE, List.of(), List.of()),
            s.sessionId(),
            extended,
            customerProps.accessTokenTtl(),
            customerProps.refreshTokenTtl(),
            now);
      }
      // 비회원은 정본 상태(제한·탈퇴)가 없다 — 세션이 살아 있으면 다시 발급
      case GuestSessionService.TYPE ->
          tokens.issue(
              new SessionTokens.Principal(
                  s.principalId(), GuestSessionService.TYPE, List.of(), List.of()),
              s.sessionId(),
              s.expiresAt(),
              guestProps.accessTokenTtl(),
              guestProps.refreshTokenTtl(),
              now);
      default -> null;
    };
  }

  /** 현재 토큰의 세션(또는 그 주체의 모든 세션) 종료. 이미 종료여도 성공. */
  public void logout(boolean allDevices) {
    Jwt jwt = currentJwt();
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          // 이미 나간 access token 도 바로 401 — 게이트웨이 Redis 차단 목록(2026-11-13)
          String type = jwt.getClaimAsString("typ");
          EventEnvelope.Actor actor =
              new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "auth-service");
          String sid = jwt.getClaimAsString("sid");
          if (allDevices) {
            store.revokeAllSessions(jwt.getSubject(), "LOGOUT_ALL", now);
            blocks.block(jwt.getSubject(), null, type, now, "LOGOUT_ALL", actor);
          } else if (sid != null) {
            store.revokeSession(sid, "LOGOUT", now);
            blocks.block(jwt.getSubject(), sid, type, now, "LOGOUT", actor);
          }
        });
  }

  public Current current() {
    Jwt jwt = currentJwt();
    String sid = jwt.getClaimAsString("sid");
    if (sid == null) {
      throw new PmartException(ErrorCode.UNSUPPORTED_ACTION, "로그인 세션 토큰이 아닙니다.");
    }
    SessionRow s =
        store
            .session(sid, false)
            .filter(x -> "ACTIVE".equals(x.state()) && x.expiresAt().isAfter(clock.instant()))
            .orElseThrow(
                () ->
                    new PmartException(
                        "SESSION_EXPIRED",
                        HttpStatus.UNAUTHORIZED,
                        "세션이 종료되었습니다. 다시 로그인하세요.",
                        false,
                        List.of()));
    List<String> roles = List.of();
    List<String> centers = List.of();
    return new Current(
        s.sessionId(),
        s.principalId(),
        s.principalType(),
        roles,
        centers,
        Objects.requireNonNull(jwt.getIssuedAt()),
        Objects.requireNonNull(jwt.getExpiresAt()));
  }

  static Jwt currentJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다.");
  }
}
