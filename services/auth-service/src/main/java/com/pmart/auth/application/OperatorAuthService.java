package com.pmart.auth.application;

import com.pmart.auth.domain.OperatorRules;
import com.pmart.auth.infrastructure.OperatorStore;
import com.pmart.auth.infrastructure.OperatorStore.AccountRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 로그인·내 비밀번호 변경(ADR-0018). 토큰 발급은 {@link SessionTokens}, 갱신·로그아웃·현재 세션은 {@link SessionService}.
 *
 * <ul>
 *   <li>access token: {@code typ=OPERATOR, roles, centers, sid}. 역할·센터는 로그인·갱신 때 계정에서 다시 읽는다.
 *   <li>로그인 실패 기록은 실패 응답과 별개 트랜잭션으로 남긴다(예외로 롤백되지 않게).
 *   <li>아이디·비밀번호·토큰은 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class OperatorAuthService {

  public static final String TYPE = "OPERATOR";

  /** 없는 아이디도 같은 비용으로 비교해 응답 시간으로 존재 여부가 드러나지 않게 한다. */
  private static final String DUMMY_HASH =
      "{bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG";

  private static final Logger log = LoggerFactory.getLogger(OperatorAuthService.class);

  public record LoginResult(SessionTokens.Tokens tokens, AccountRow account) {}

  private enum LoginOutcome {
    OK,
    FAILED,
    LOCKED
  }

  private final OperatorStore store;
  private final PasswordEncoder encoder;
  private final SessionTokens tokens;
  private final OperatorProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OperatorAuthService(
      OperatorStore store,
      PasswordEncoder encoder,
      SessionTokens tokens,
      OperatorProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.encoder = encoder;
    this.tokens = tokens;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public LoginResult login(
      String loginId, String password, String sourceSystem, @Nullable String deviceId) {
    Instant now = clock.instant();
    LoginResult[] ok = new LoginResult[1];
    Instant[] lockedUntil = new Instant[1];
    LoginOutcome outcome =
        tx.execute(
            status -> {
              AccountRow a = store.byLoginId(loginId, true).orElse(null);
              boolean matches =
                  encoder.matches(password, a == null ? DUMMY_HASH : a.passwordHash());
              if (a == null) {
                return LoginOutcome.FAILED;
              }
              if (OperatorRules.locked(a.lockedUntil(), now)) {
                lockedUntil[0] = a.lockedUntil();
                return LoginOutcome.LOCKED;
              }
              if (!matches || !"ACTIVE".equals(a.state())) {
                OperatorRules.Failure f =
                    OperatorRules.fail(
                        a.failedCount(), props.maxFailedAttempts(), props.lockDuration(), now);
                store.recordFailure(a.operatorId(), f.failedCount(), f.lockedUntil(), now);
                log.warn("운영자 로그인 실패: operatorId={}", a.operatorId());
                return LoginOutcome.FAILED;
              }
              store.recordLogin(a.operatorId(), now);
              String sessionId = "SES-" + UUID.randomUUID();
              Instant sessionExpires = now.plus(props.sessionMaxAge());
              store.insertSession(
                  sessionId, a.operatorId(), TYPE, deviceId, sourceSystem, now, sessionExpires);
              AccountRow fresh = store.byId(a.operatorId(), false).orElseThrow();
              ok[0] = new LoginResult(issue(fresh, sessionId, sessionExpires, now), fresh);
              return LoginOutcome.OK;
            });
    return switch (Objects.requireNonNull(outcome)) {
      case OK -> ok[0];
      case LOCKED ->
          throw new PmartException(
              "ACCOUNT_LOCKED",
              HttpStatus.LOCKED,
              "연속 로그인 실패로 잠겼습니다. " + lockedUntil[0] + " 이후 다시 시도하세요.",
              false,
              List.of());
      case FAILED -> throw loginFailed();
    };
  }

  public void changeOwnPassword(String currentPassword, String newPassword) {
    Jwt jwt = SessionService.currentJwt();
    if (!TYPE.equals(jwt.getClaimAsString("typ"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다.");
    }
    Instant now = clock.instant();
    Boolean done =
        tx.execute(
            status -> {
              AccountRow a = store.byId(jwt.getSubject(), true).orElseThrow();
              if (!encoder.matches(currentPassword, a.passwordHash())) {
                return false;
              }
              OperatorRules.passwordProblem(newPassword, a.loginId(), props.passwordMinLength())
                  .ifPresent(
                      p -> {
                        throw passwordPolicy(p);
                      });
              store.setPassword(a.operatorId(), encoder.encode(newPassword), false, now);
              return true;
            });
    if (!Boolean.TRUE.equals(done)) {
      throw loginFailed();
    }
  }

  private SessionTokens.Tokens issue(
      AccountRow a, String sessionId, Instant sessionExpires, Instant now) {
    return tokens.issue(
        new SessionTokens.Principal(a.operatorId(), TYPE, a.roles(), a.centers()),
        sessionId,
        sessionExpires,
        props.accessTokenTtl(),
        props.refreshTokenTtl(),
        now);
  }

  static PmartException loginFailed() {
    return new PmartException(
        "LOGIN_FAILED", HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다.", false, List.of());
  }

  static PmartException passwordPolicy(String message) {
    return new PmartException(
        "PASSWORD_POLICY", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
