package com.pmart.auth.application;

import com.pmart.auth.domain.OperatorRules;
import com.pmart.auth.infrastructure.OperatorStore;
import com.pmart.auth.infrastructure.OperatorStore.AccountRow;
import com.pmart.auth.infrastructure.OperatorStore.RefreshRow;
import com.pmart.auth.infrastructure.OperatorStore.SessionRow;
import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 로그인·토큰 갱신·로그아웃·현재 세션·내 비밀번호 변경(ADR-0018).
 *
 * <ul>
 *   <li>access token: {@code typ=OPERATOR, roles, centers, sid}. 역할·센터는 로그인·갱신 때 계정에서 다시 읽는다.
 *   <li>refresh token: 무작위 값, DB 에는 SHA-256 만. 한 번만 쓰고(회전), 쓴 토큰이 다시 오면 탈취로 보고 세션을 폐기한다.
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
  private static final SecureRandom RANDOM = new SecureRandom();

  public record Tokens(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      String sessionId) {}

  public record LoginResult(Tokens tokens, AccountRow account) {}

  public record CurrentSession(
      SessionRow session, AccountRow account, Instant issuedAt, Instant expiresAt) {}

  private enum LoginOutcome {
    OK,
    FAILED,
    LOCKED
  }

  private final OperatorStore store;
  private final PasswordEncoder encoder;
  private final JwtEncoder jwtEncoder;
  private final SigningKeyRing keys;
  private final OperatorProperties props;
  private final String issuer;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OperatorAuthService(
      OperatorStore store,
      PasswordEncoder encoder,
      JwtEncoder jwtEncoder,
      SigningKeyRing keys,
      OperatorProperties props,
      AuthProperties authProperties,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.encoder = encoder;
    this.jwtEncoder = jwtEncoder;
    this.keys = keys;
    this.props = props;
    this.issuer = authProperties.issuer();
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

  public Tokens refresh(String refreshToken, @Nullable String deviceId) {
    Instant now = clock.instant();
    String[] reason = new String[1];
    Tokens result =
        tx.execute(
            status -> {
              RefreshRow r = store.lockRefresh(hash(refreshToken)).orElse(null);
              if (r == null) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionRow s = store.session(r.sessionId(), true).orElseThrow();
              if (r.usedAt() != null) {
                // 이미 쓴 refresh token 이 다시 왔다 — 탈취 가능성, 세션 전체 폐기
                store.revokeSession(s.sessionId(), "REFRESH_REUSED", now);
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
              AccountRow a = store.byId(s.principalId(), false).orElse(null);
              if (a == null || !"ACTIVE".equals(a.state())) {
                store.revokeSession(s.sessionId(), "ACCOUNT_DISABLED", now);
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              store.markRefreshUsed(r.tokenHash(), now);
              store.markRefreshed(s.sessionId(), now);
              return issue(a, s.sessionId(), s.expiresAt(), now);
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

  /** 현재 토큰의 세션(또는 그 주체의 모든 세션) 종료. 이미 종료여도 성공. */
  public void logout(boolean allDevices) {
    Jwt jwt = currentJwt();
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

  public CurrentSession current() {
    Jwt jwt = currentJwt();
    String sid = jwt.getClaimAsString("sid");
    if (sid == null || !TYPE.equals(jwt.getClaimAsString("typ"))) {
      throw new PmartException(ErrorCode.UNSUPPORTED_ACTION, "운영자 세션만 조회할 수 있습니다.");
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
    AccountRow a = store.byId(s.principalId(), false).orElseThrow();
    return new CurrentSession(
        s,
        a,
        Objects.requireNonNull(jwt.getIssuedAt()),
        Objects.requireNonNull(jwt.getExpiresAt()));
  }

  public void changeOwnPassword(String currentPassword, String newPassword) {
    Jwt jwt = currentJwt();
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

  private Tokens issue(AccountRow a, String sessionId, Instant sessionExpires, Instant now) {
    Instant accessExpires = min(now.plus(props.accessTokenTtl()), sessionExpires);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(a.operatorId())
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(accessExpires)
            .id(UUID.randomUUID().toString())
            .claim("typ", TYPE)
            .claim("roles", a.roles())
            .claim("centers", a.centers())
            .claim("sid", sessionId)
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKey().getKeyID()).build();
    String access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant refreshExpires = min(now.plus(props.refreshTokenTtl()), sessionExpires);
    store.insertRefresh(hash(refresh), sessionId, now, refreshExpires);
    return new Tokens(access, accessExpires, refresh, refreshExpires, sessionId);
  }

  static PmartException loginFailed() {
    return new PmartException(
        "LOGIN_FAILED", HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다.", false, List.of());
  }

  static PmartException passwordPolicy(String message) {
    return new PmartException(
        "PASSWORD_POLICY", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  static Jwt currentJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    if (auth != null && auth.getCredentials() instanceof Jwt jwt) {
      return jwt;
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다.");
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
