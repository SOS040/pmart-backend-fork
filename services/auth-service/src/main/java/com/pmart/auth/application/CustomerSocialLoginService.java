package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.CustomerStore.AuthRequestRow;
import com.pmart.auth.infrastructure.GoogleIdTokenVerifier;
import com.pmart.auth.infrastructure.OperatorStore;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객 소셜 로그인(Commerce 4.9, auth-service.yaml socialLogin). 지금은 고객 앱의 Google ID 토큰만 받는다(D6).
 *
 * <ul>
 *   <li>제공자 계정(sub)으로 내부 계정을 찾고 없으면 만든다. 이메일이 같다는 이유로 계정을 합치지 않는다.
 *   <li>필수 동의 단계는 설정({@code require-consent})이 켜졌을 때만 AUTH_CONSENT. 지금은 꺼져 있어 바로 AUTH_MEMBER(사용자 결정
 *       2026-10-30).
 *   <li>기기별 15분 내 5회 명확 실패(PROVIDER_REJECTED) → 10분 AUTH_RESTRICTED. 제공자 장애는 세지 않고, 제한 중 시도는 기간을
 *       늘리지 않는다.
 *   <li>같은 Idempotency-Key 재전송은 원인증 결과를 따른다: 회원이면 새 세션 토큰을 주고(토큰은 저장하지 않음), 실패였으면 같은 실패.
 *   <li>회원이 되면 CustomerAuthenticated(platform.auth.v1). 인증 성공은 구매 자격이 아니다.
 * </ul>
 */
@Service
public class CustomerSocialLoginService {

  public static final String TYPE = "CUSTOMER";
  public static final String TOPIC = "platform.auth.v1";

  private static final Logger log = LoggerFactory.getLogger(CustomerSocialLoginService.class);

  public record Result(
      String authRequestId,
      String state,
      @Nullable String accountId,
      boolean newAccount,
      SessionTokens.@Nullable Tokens tokens,
      String nextAction,
      Instant occurredAt) {}

  /** AUTH_RESTRICTED(423). */
  public static class RestrictedException extends RuntimeException {
    private final Instant until;
    private final long retryAfterSeconds;

    public RestrictedException(Instant until, long retryAfterSeconds) {
      super("로그인 실패가 많아 잠시 제한되었습니다. " + retryAfterSeconds + "초 뒤 다시 시도하세요.");
      this.until = until;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public Instant until() {
      return until;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  private final CustomerStore customers;
  private final OperatorStore sessions;
  private final SessionTokens tokens;
  private final GoogleIdTokenVerifier google;
  private final CustomerProperties props;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CustomerSocialLoginService(
      CustomerStore customers,
      OperatorStore sessions,
      SessionTokens tokens,
      GoogleIdTokenVerifier google,
      CustomerProperties props,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TransactionTemplate tx,
      Clock clock) {
    this.customers = customers;
    this.sessions = sessions;
    this.tokens = tokens;
    this.google = google;
    this.props = props;
    this.events = events;
    this.codec = codec;
    this.tx = tx;
    this.clock = clock;
  }

  public Result login(
      String idempotencyKey,
      String sourceSystem,
      String deviceId,
      String provider,
      String providerToken) {
    if (!"GOOGLE".equals(provider)) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, provider + " 로그인은 아직 지원하지 않습니다(GOOGLE 만).");
    }
    if (props.google().clientIds().isEmpty()) {
      throw PmartException.configRequired("pmart.auth.customer.google.client-ids");
    }
    // 같은 키 재전송: 원결과를 따른다
    Optional<AuthRequestRow> earlier = customers.requestByKey(sourceSystem, idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), deviceId, sourceSystem);
    }
    Instant now = now();
    Optional<Instant> restricted = customers.restrictedUntil(deviceId).filter(u -> u.isAfter(now));
    if (restricted.isPresent()) {
      throw restrictedUntil(restricted.get(), now);
    }

    // 제공자 검증은 트랜잭션 밖(네트워크)
    GoogleIdTokenVerifier.Identity identity;
    try {
      identity = google.verify(providerToken);
    } catch (GoogleIdTokenVerifier.Unavailable e) {
      log.warn("Google 검증 불가(실패 횟수 제외): {}", e.getMessage());
      throw new PmartException(
          "PROVIDER_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "Google 인증 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    } catch (GoogleIdTokenVerifier.Rejected e) {
      return rejected(idempotencyKey, sourceSystem, deviceId, provider, now);
    }

    Result r =
        tx.execute(
            status -> {
              String accountId = customers.linkedAccount(provider, identity.subject()).orElse(null);
              boolean created = false;
              if (accountId == null) {
                String candidate = "CU-" + UUID.randomUUID();
                customers.insertAccount(candidate, now);
                if (customers.insertIdentity(provider, identity.subject(), candidate, now) == 1) {
                  accountId = candidate;
                  created = true;
                } else {
                  // 같은 제공자 계정이 동시에 가입됨 — 먼저 만든 쪽을 쓴다(만든 빈 계정은 롤백으로 지운다)
                  status.setRollbackOnly();
                  return null;
                }
              }
              String state = customers.accountState(accountId).orElse("ACTIVE");
              if (!"ACTIVE".equals(state)) {
                throw new PmartException(
                    "ACCOUNT_NOT_AVAILABLE",
                    HttpStatus.FORBIDDEN,
                    "이용할 수 없는 계정입니다(" + state + ").",
                    false,
                    List.of());
              }
              String authState = created && props.requireConsent() ? "AUTH_CONSENT" : "AUTH_MEMBER";
              String authRequestId = "AR-" + UUID.randomUUID();
              customers.insertRequest(
                  authRequestId,
                  sourceSystem,
                  idempotencyKey,
                  deviceId,
                  provider,
                  authState,
                  null,
                  accountId,
                  created,
                  now);
              customers.recordLogin(provider, identity.subject(), accountId, now);
              SessionTokens.Tokens t = null;
              if ("AUTH_MEMBER".equals(authState)) {
                t = openSession(accountId, deviceId, sourceSystem, now);
                publishAuthenticated(accountId, provider, now, identity);
              }
              return new Result(
                  authRequestId,
                  authState,
                  accountId,
                  created,
                  t,
                  "AUTH_MEMBER".equals(authState) ? "NONE" : "COMPLETE_CONSENT",
                  now);
            });
    if (r == null) {
      // 경합에서 진 요청: 이제 연결이 있으니 다시 한 번
      return login(idempotencyKey, sourceSystem, deviceId, provider, providerToken);
    }
    log.info("고객 소셜 로그인 {}: accountId={} new={}", r.state(), r.accountId(), r.newAccount());
    return r;
  }

  /** 원인증 결과 조회(같은 기기만). 토큰은 다시 주지 않는다. */
  public Result result(String authRequestId, String deviceId) {
    AuthRequestRow row =
        customers
            .request(authRequestId)
            .orElseThrow(() -> PmartException.notFound("AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
    if (!row.deviceId().equals(deviceId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인을 시작한 기기에서만 조회할 수 있습니다.");
    }
    return new Result(
        row.authRequestId(),
        row.state(),
        row.accountId(),
        row.newAccount(),
        null,
        "AUTH_MEMBER".equals(row.state()) ? "NONE" : "QUERY_RESULT",
        row.createdAt());
  }

  private Result replay(AuthRequestRow row, String deviceId, String sourceSystem) {
    if (!row.deviceId().equals(deviceId)) {
      throw PmartException.idempotencyConflict("같은 Idempotency-Key 로 다른 기기의 요청이 이미 처리되었습니다.");
    }
    if (row.failureCode() != null) {
      throw providerRejected();
    }
    Instant now = now();
    SessionTokens.Tokens t =
        "AUTH_MEMBER".equals(row.state())
            ? tx.execute(
                status ->
                    openSession(
                        Objects.requireNonNull(row.accountId()), deviceId, sourceSystem, now))
            : null;
    return new Result(
        row.authRequestId(),
        row.state(),
        row.accountId(),
        row.newAccount(),
        t,
        "AUTH_MEMBER".equals(row.state()) ? "NONE" : "COMPLETE_CONSENT",
        row.createdAt());
  }

  private Result rejected(
      String idempotencyKey, String sourceSystem, String deviceId, String provider, Instant now) {
    Instant[] until = new Instant[1];
    tx.executeWithoutResult(
        status -> {
          customers.lockDevice(deviceId);
          customers.insertRequest(
              "AR-" + UUID.randomUUID(),
              sourceSystem,
              idempotencyKey,
              deviceId,
              provider,
              "AUTH_GUEST",
              "PROVIDER_REJECTED",
              null,
              false,
              now);
          if (customers.failuresSince(deviceId, now.minus(props.failureWindow()))
              >= props.maxFailures()) {
            until[0] = now.plus(props.restriction());
            customers.restrict(deviceId, until[0]);
          }
        });
    log.warn("고객 소셜 로그인 거절(PROVIDER_REJECTED) provider={}", provider);
    if (until[0] != null) {
      throw restrictedUntil(until[0], now);
    }
    throw providerRejected();
  }

  private SessionTokens.Tokens openSession(
      String accountId, String deviceId, String sourceSystem, Instant now) {
    String sessionId = "SES-" + UUID.randomUUID();
    Instant sessionExpires = now.plus(props.sessionMaxAge());
    sessions.insertSession(sessionId, accountId, TYPE, deviceId, sourceSystem, now, sessionExpires);
    return tokens.issue(
        new SessionTokens.Principal(accountId, TYPE, List.of(), List.of()),
        sessionId,
        sessionExpires,
        props.accessTokenTtl(),
        props.refreshTokenTtl(),
        now);
  }

  /** Google 이 준 프로필(이름·이메일·사진)은 저장하지 않고 사건으로 customer-service 에 넘긴다(ADR-0024). 없는 값은 싣지 않는다. */
  private void publishAuthenticated(
      String accountId, String provider, Instant at, GoogleIdTokenVerifier.Identity identity) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", accountId);
    payload.put("provider", provider);
    payload.put("authenticatedAt", at.toString());
    putIfPresent(payload, "email", identity.email());
    putIfPresent(payload, "emailVerified", identity.emailVerified());
    putIfPresent(payload, "name", identity.name());
    putIfPresent(payload, "pictureUrl", identity.pictureUrl());
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            "CustomerAuthenticated",
            1,
            "Account",
            accountId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, accountId),
            EventEnvelope.EventSource.REAL,
            payload);
    // 계약 토픽(집합 이름 Account 의 기본 토픽이 아님)
    events.publish(envelope, TOPIC);
  }

  private static void putIfPresent(Map<String, Object> m, String key, @Nullable Object v) {
    if (v != null) {
      m.put(key, v);
    }
  }

  /** DB(timestamptz, 마이크로초)에 저장한 값과 응답 값이 같도록 마이크로초로 자른다. */
  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  static PmartException providerRejected() {
    return new PmartException(
        "PROVIDER_REJECTED",
        HttpStatus.UNAUTHORIZED,
        "Google 인증 결과를 확인할 수 없습니다. 다시 로그인하세요.",
        false,
        List.of());
  }

  static RestrictedException restrictedUntil(Instant until, Instant now) {
    return new RestrictedException(until, Math.max(1, Duration.between(now, until).toSeconds()));
  }
}
