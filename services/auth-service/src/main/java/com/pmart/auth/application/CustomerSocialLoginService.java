package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerConsentClient;
import com.pmart.auth.infrastructure.CustomerConsentClient.ConsentCheck;
import com.pmart.auth.infrastructure.CustomerIdentityClient;
import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.CustomerStore.AuthRequestRow;
import com.pmart.auth.infrastructure.IdTokenVerifier;
import com.pmart.auth.infrastructure.IdTokenVerifiers;
import com.pmart.auth.infrastructure.SessionStore;
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
 *   <li>필수 약관 동의(CU-1, 2026-11-17): 설정({@code require-consent})이 켜져 있으면 customer-service 에 현행 버전 기준
 *       동의 상태를 묻고, 빠진 필수 항목이 있으면(신규 가입·약관 버전이 바뀐 뒤의 기존 회원) AUTH_CONSENT — 토큰 없이 항목 목록만 준다. 고객이 동의
 *       화면에서 고른 값은 {@link #complete} 로 받아 customer 에 넘긴 뒤 토큰을 준다. Google
 *       프로필(CustomerAuthenticated)도 그때 넘긴다 — 동의 전에는 개인정보를 넘기지도 저장하지도 않는다.
 *   <li>기기별 15분 내 5회 명확 실패(PROVIDER_REJECTED) → 10분 AUTH_RESTRICTED. 제공자·customer 장애는 세지 않고, 제한 중
 *       시도는 기간을 늘리지 않는다.
 *   <li>같은 Idempotency-Key 재전송은 원인증 결과를 따른다: 회원이면 새 세션 토큰을 주고(토큰은 저장하지 않음), 동의 대기면 같은 항목, 실패였으면 같은
 *       실패.
 *   <li>회원이 되면 CustomerAuthenticated(platform.auth.v1). 인증 성공은 구매 자격이 아니다.
 * </ul>
 */
@Service
public class CustomerSocialLoginService {

  public static final String TYPE = "CUSTOMER";
  public static final String TOPIC = "platform.auth.v1";

  private static final String MEMBER = "AUTH_MEMBER";
  private static final String CONSENT = "AUTH_CONSENT";
  private static final String IDENTITY = "AUTH_IDENTITY";
  private static final String VERIFY_IDENTITY = "VERIFY_IDENTITY";

  private static final Logger log = LoggerFactory.getLogger(CustomerSocialLoginService.class);

  /**
   * @param newAccount 가입이 이번에 끝나는(또는 동의 뒤 끝날) 계정 — 새로 만들었거나 전에 동의 단계에서 나가 회원이 된 적이 없는 계정
   * @param requiredConsents AUTH_CONSENT 일 때 반드시 동의할 항목 {@code TYPE:version}
   * @param optionalConsents AUTH_CONSENT 일 때 함께 보여 줄 선택 항목
   */
  public record Result(
      String authRequestId,
      String state,
      @Nullable String accountId,
      boolean newAccount,
      SessionTokens.@Nullable Tokens tokens,
      List<String> requiredConsents,
      List<String> optionalConsents,
      String nextAction,
      Instant occurredAt,
      @Nullable IdentityStatus identity) {

    Result(
        String authRequestId,
        String state,
        @Nullable String accountId,
        boolean newAccount,
        SessionTokens.@Nullable Tokens tokens,
        List<String> requiredConsents,
        List<String> optionalConsents,
        String nextAction,
        Instant occurredAt) {
      this(
          authRequestId,
          state,
          accountId,
          newAccount,
          tokens,
          requiredConsents,
          optionalConsents,
          nextAction,
          occurredAt,
          null);
    }
  }

  /** 휴대폰 확인 진행 상태(확인을 눌렀는데 아직 끝나지 않음 — ADR-0074). */
  public record IdentityStatus(
      String verificationId,
      String state,
      @Nullable String failureReason,
      int remainingAttempts,
      Instant expiresAt) {}

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
  private final SessionStore sessions;
  private final SessionTokens tokens;
  private final IdTokenVerifiers verifiers;
  private final CustomerConsentClient consents;
  private final CustomerProperties props;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final CustomerIdentityClient identities;

  /** 네이버 교환 때 받은 이메일(BR-77). */
  private final com.pmart.auth.infrastructure.NaverProfileEmailStore naverEmails;

  private final CustomerSuspensions suspensions; // 2026-11-23 CU-7

  public CustomerSocialLoginService(
      CustomerStore customers,
      SessionStore sessions,
      SessionTokens tokens,
      IdTokenVerifiers verifiers,
      CustomerConsentClient consents,
      CustomerProperties props,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TransactionTemplate tx,
      Clock clock,
      CustomerIdentityClient identities,
      com.pmart.auth.infrastructure.NaverProfileEmailStore naverEmails,
      CustomerSuspensions suspensions) {
    this.naverEmails = naverEmails;
    this.suspensions = suspensions;
    this.identities = identities;
    this.customers = customers;
    this.sessions = sessions;
    this.tokens = tokens;
    this.verifiers = verifiers;
    this.consents = consents;
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
      String providerToken,
      @Nullable String profileImageUrl) {
    // GOOGLE·KAKAO·NAVER 는 같은 방식(ID 토큰 검증, ADR-0055·ADR-0071). APPLE 은 501, 클라이언트 ID 설정이 없으면 422
    IdTokenVerifier verifier = verifiers.require(provider);
    // 같은 키 재전송: 원결과를 따른다
    Optional<AuthRequestRow> earlier = customers.requestByKey(sourceSystem, idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), deviceId, sourceSystem);
    }
    Instant now = now();
    checkRestriction(deviceId, now);

    // 제공자 검증은 트랜잭션 밖(네트워크)
    IdTokenVerifier.Identity identity;
    try {
      identity = verifier.verify(providerToken);
    } catch (IdTokenVerifier.Unavailable e) {
      throw providerUnavailable(e);
    } catch (IdTokenVerifier.Rejected e) {
      return rejected(idempotencyKey, sourceSystem, deviceId, provider, now);
    }

    // 필수 동의 판단도 트랜잭션 밖(customer-service 호출). 가입이면 만들 계정 ID 로 묻는다 — customer 에 행이 없어 모두 빠진 것으로 답한다
    String candidate = "CU-" + UUID.randomUUID();
    // 가입을 끝내지 않고 나갔던 계정(연결은 있으나 회원이 된 적 없음)은 처음부터 — 저장된 동의·기기 동의를 무시하고 전부 다시 묻는다
    // (2026-11-20 BR-72, 사용자 "(가)로 해줘, 1번"). 이미 회원인 계정의 재동의·첫 가입은 그대로
    String linkedForConsent = customers.linkedAccount(provider, identity.subject()).orElse(null);
    boolean restartSignup = linkedForConsent != null && !customers.everMember(linkedForConsent);
    ConsentCheck check =
        props.requireConsent()
            ? consents.check(
                linkedForConsent == null ? candidate : linkedForConsent, deviceId, restartSignup)
            : null;

    // 휴대폰 확인 판단(ADR-0074, 스위치) — 트랜잭션 밖. 가입(연결 없음)은 늘 필요, 기존 계정은 customer 에 확인 번호가 있는지
    String linkedBefore = customers.linkedAccount(provider, identity.subject()).orElse(null);
    boolean needsIdentity =
        props.requireIdentity() && (linkedBefore == null || !identities.verified(linkedBefore));

    Result r =
        tx.execute(
            status -> {
              String accountId = customers.linkedAccount(provider, identity.subject()).orElse(null);
              boolean created = false;
              if (accountId == null) {
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
                throw accountNotAvailable(state);
              }
              suspensions.check(accountId); // 2026-11-23 CU-7 정지 기한 안이면 403
              // 동의 단계에서 나갔던 가입은 다음 로그인도 가입이다(회원이 된 적 없음)
              boolean signup = created || !customers.everMember(accountId);
              boolean needsConsent = check != null && !check.satisfied();
              String authState = needsConsent ? CONSENT : needsIdentity ? IDENTITY : MEMBER;
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
                  signup,
                  now);
              customers.recordLogin(provider, identity.subject(), accountId, now);
              if (needsConsent) {
                customers.recordConsentsAsked(
                    authRequestId, check.missingRequired(), check.undecidedOptional());
                return new Result(
                    authRequestId,
                    CONSENT,
                    accountId,
                    signup,
                    null,
                    check.missingRequired(),
                    check.undecidedOptional(),
                    "COMPLETE_CONSENT",
                    now);
              }
              if (needsIdentity) {
                return new Result(
                    authRequestId,
                    IDENTITY,
                    accountId,
                    signup,
                    null,
                    List.of(),
                    List.of(),
                    VERIFY_IDENTITY,
                    now);
              }
              customers.markMember(accountId, now);
              SessionTokens.Tokens t = openSession(accountId, deviceId, sourceSystem, now);
              publishAuthenticated(accountId, provider, now, identity, profileImageUrl);
              return new Result(
                  authRequestId, MEMBER, accountId, signup, t, List.of(), List.of(), "NONE", now);
            });
    if (r == null) {
      // 경합에서 진 요청: 이제 연결이 있으니 다시 한 번
      return login(
          idempotencyKey, sourceSystem, deviceId, provider, providerToken, profileImageUrl);
    }
    log.info("고객 소셜 로그인 {}: accountId={} new={}", r.state(), r.accountId(), r.newAccount());
    if (MEMBER.equals(r.state()) && r.accountId() != null) {
      moveDeviceConsents(r.accountId(), deviceId);
    }
    return r;
  }

  /**
   * 로그인 전 기기 동의를 계정으로(2026-11-19 BR-59). 회원 토큰을 준 뒤, 트랜잭션 밖에서 한 번 — 실패해도 로그인은 그대로(기기 기록이 남아 다음 로그인
   * 때 다시 옮긴다. 그 사이 로그인 동의 판단은 기기 결정을 결정한 것으로 본다).
   */
  private void moveDeviceConsents(String accountId, String deviceId) {
    try {
      consents.migrateDevice(accountId, deviceId);
    } catch (RuntimeException e) {
      log.warn(
          "기기 동의 옮기기 실패 accountId={} ({}) — 다음 로그인 때 다시", accountId, e.getClass().getSimpleName());
    }
  }

  /**
   * 필수 동의 완료(AUTH_CONSENT → AUTH_MEMBER, auth-service.yaml completeSocialLogin 0.2.0). 같은 기기·로그인한 그
   * Google 계정만. 동의는 customer-service 에 넘기고(정본), 필수 항목이 다 찼으면 세션 토큰과 CustomerAuthenticated(다시 검증한 ID
   * 토큰의 프로필).
   *
   * <p>같은 Idempotency-Key 재전송(응답 유실)은 새 토큰을 준다. 다른 키로 이미 끝난 요청은 409.
   */
  public Result complete(
      String authRequestId,
      String idempotencyKey,
      String sourceSystem,
      String deviceId,
      String providerToken,
      List<ConsentDecision> decisions,
      @Nullable String profileImageUrl) {
    AuthRequestRow row =
        customers
            .request(authRequestId)
            .orElseThrow(() -> PmartException.notFound("AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
    if (!row.deviceId().equals(deviceId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인을 시작한 기기에서만 동의를 마칠 수 있습니다.");
    }
    String accountId = row.accountId();
    boolean pending = CONSENT.equals(row.state());
    boolean replay = MEMBER.equals(row.state()) && idempotencyKey.equals(row.completionKey());
    if (accountId != null && row.failureCode() == null && IDENTITY.equals(row.state())) {
      // 동의는 이미 반영됐고 휴대폰 확인이 남음(응답 유실 재시도) — 같은 안내
      return identityResult(row, now());
    }
    if (accountId == null || row.failureCode() != null || !(pending || replay)) {
      throw PmartException.invalidState("필수 동의를 기다리는 로그인 요청이 아닙니다(AUTH_CONSENT 아님).");
    }
    Instant now = now();
    checkRestriction(deviceId, now);

    // 로그인한 그 소셜 계정인지 다시 확인한다(토큰이 없는 단계라 이것이 본인 확인). 프로필은 동의 뒤에만 넘긴다
    IdTokenVerifier.Identity identity;
    try {
      identity = verifiers.require(row.provider()).verify(providerToken);
    } catch (IdTokenVerifier.Unavailable e) {
      throw providerUnavailable(e);
    } catch (IdTokenVerifier.Rejected e) {
      log.warn("필수 동의 완료 거절(PROVIDER_REJECTED) provider={}", row.provider());
      throw providerRejected(row.provider());
    }
    if (!accountId.equals(
        customers.linkedAccount(row.provider(), identity.subject()).orElse(null))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인한 계정과 다른 소셜 계정입니다.");
    }

    if (pending) {
      // customer 쪽 멱등 키 = 이 요청의 키(같은 키 재전송은 다시 반영하지 않는다). 트랜잭션 밖(네트워크)
      ConsentCheck after;
      try {
        after =
            consents.change(
                accountId, idempotencyKey, row.newAccount() ? "SIGNUP" : "LOGIN", decisions);
      } catch (PmartException e) {
        // 가입·재동의 화면에서 필수 항목을 끈 것은 철회가 아니라 "빠짐"
        if ("REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN".equals(e.code())) {
          throw requiredMissing(row.requiredConsents());
        }
        throw e;
      }
      if (!after.satisfied()) {
        throw requiredMissing(after.missingRequired());
      }
    }
    boolean needsIdentity = pending && props.requireIdentity() && !identities.verified(accountId);

    Result r =
        Objects.requireNonNull(
            tx.execute(
                status -> {
                  AuthRequestRow locked =
                      customers
                          .lockRequest(authRequestId)
                          .orElseThrow(
                              () ->
                                  PmartException.notFound(
                                      "AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
                  if (MEMBER.equals(locked.state())) {
                    if (!idempotencyKey.equals(locked.completionKey())) {
                      throw PmartException.invalidState("이미 다른 요청으로 로그인을 마쳤습니다.");
                    }
                    // 응답 유실 재시도: 새 세션만(사건은 이미 냈다)
                    return memberResult(
                        locked, openSession(accountId, deviceId, sourceSystem, now), now);
                  }
                  if (!CONSENT.equals(locked.state())) {
                    throw PmartException.invalidState("필수 동의를 기다리는 로그인 요청이 아닙니다.");
                  }
                  String state = customers.accountState(accountId).orElse("ACTIVE");
                  if (!"ACTIVE".equals(state)) {
                    throw accountNotAvailable(state);
                  }
                  suspensions.check(accountId); // 2026-11-23 CU-7 정지 기한 안이면 403
                  if (needsIdentity) {
                    // 동의는 끝, 휴대폰 확인이 남음(ADR-0074) — 토큰·사건은 확인 뒤
                    customers.consentDoneIdentityNext(authRequestId);
                    return identityResult(locked, now);
                  }
                  customers.completeConsent(authRequestId, idempotencyKey, now);
                  customers.markMember(accountId, now);
                  SessionTokens.Tokens t = openSession(accountId, deviceId, sourceSystem, now);
                  publishAuthenticated(accountId, row.provider(), now, identity, profileImageUrl);
                  return memberResult(locked, t, now);
                }));
    log.info("고객 필수 동의 완료: accountId={} new={} → {}", accountId, r.newAccount(), r.state());
    if (MEMBER.equals(r.state())) {
      moveDeviceConsents(accountId, deviceId);
    }
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
    boolean consent = CONSENT.equals(row.state());
    return new Result(
        row.authRequestId(),
        row.state(),
        row.accountId(),
        row.newAccount(),
        null,
        consent ? row.requiredConsents() : List.of(),
        consent ? row.optionalConsents() : List.of(),
        MEMBER.equals(row.state())
            ? "NONE"
            : consent
                ? "COMPLETE_CONSENT"
                : IDENTITY.equals(row.state()) ? VERIFY_IDENTITY : "QUERY_RESULT",
        row.createdAt());
  }

  private Result replay(AuthRequestRow row, String deviceId, String sourceSystem) {
    if (!row.deviceId().equals(deviceId)) {
      throw PmartException.idempotencyConflict("같은 Idempotency-Key 로 다른 기기의 요청이 이미 처리되었습니다.");
    }
    if (row.failureCode() != null) {
      throw providerRejected(row.provider());
    }
    Instant now = now();
    boolean member = MEMBER.equals(row.state());
    SessionTokens.Tokens t =
        member
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
        member || IDENTITY.equals(row.state()) ? List.of() : row.requiredConsents(),
        member || IDENTITY.equals(row.state()) ? List.of() : row.optionalConsents(),
        member ? "NONE" : IDENTITY.equals(row.state()) ? VERIFY_IDENTITY : "COMPLETE_CONSENT",
        row.createdAt());
  }

  // ---------------------------------------------------------------- 휴대폰 확인(ADR-0074)

  /**
   * 휴대폰 확인 시작(AUTH_IDENTITY). 같은 기기·로그인한 그 소셜 계정만(토큰이 없는 단계라 ID 토큰을 다시 검증해 본인 확인). 번호 정본은 customer
   * — 같은 Idempotency-Key 는 customer 가 같은 결과를 준다. 번호는 로그에 남기지 않는다.
   */
  public CustomerIdentityClient.Started startIdentity(
      String authRequestId,
      String idempotencyKey,
      String deviceId,
      String providerToken,
      String phone) {
    AuthRequestRow row = identityRequest(authRequestId, deviceId);
    checkRestriction(deviceId, now());
    reverify(row, providerToken);
    return identities.start(Objects.requireNonNull(row.accountId()), phone, idempotencyKey);
  }

  /**
   * 휴대폰 확인 → 회원(AUTH_IDENTITY → AUTH_MEMBER). customer 가 문자를 확인하고 번호 규칙을 본다:
   *
   * <ul>
   *   <li>VERIFIED → 원인증 AUTH_MEMBER·세션·CustomerAuthenticated(다시 검증한 토큰의 프로필)
   *   <li>아직 문자가 없음·횟수 다 씀·시간 지남 → AUTH_IDENTITY 그대로·진행 상태
   *   <li>다른 계정의 번호 → 409 IDENTITY_ALREADY_REGISTERED(그 계정의 제공자·가입일·가린 이메일), 탈퇴 막기 → 409
   *       IDENTITY_REJOIN_BLOCKED. 이번에 만든(회원이 된 적 없는) 계정은 버린다
   * </ul>
   *
   * 같은 Idempotency-Key 재전송(이미 MEMBER)은 새 토큰.
   */
  public Result confirmIdentity(
      String authRequestId,
      String verificationId,
      String idempotencyKey,
      String sourceSystem,
      String deviceId,
      String providerToken,
      @Nullable String profileImageUrl) {
    AuthRequestRow row =
        customers
            .request(authRequestId)
            .orElseThrow(() -> PmartException.notFound("AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
    if (!row.deviceId().equals(deviceId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인을 시작한 기기에서만 확인할 수 있습니다.");
    }
    String accountId = row.accountId();
    boolean replay = MEMBER.equals(row.state()) && idempotencyKey.equals(row.completionKey());
    if (accountId == null
        || row.failureCode() != null
        || !(IDENTITY.equals(row.state()) || replay)) {
      throw PmartException.invalidState("휴대폰 확인을 기다리는 로그인 요청이 아닙니다(AUTH_IDENTITY 아님).");
    }
    Instant now = now();
    checkRestriction(deviceId, now);
    IdTokenVerifier.Identity identity = reverify(row, providerToken);

    CustomerIdentityClient.Confirmed c =
        replay ? null : identities.confirm(accountId, verificationId, idempotencyKey);
    if (c != null && !"VERIFIED".equals(c.outcome())) {
      if (IDENTITY_ALREADY_REGISTERED.equals(c.outcome())) {
        throw alreadyRegistered(row, c);
      }
      if (IDENTITY_REJOIN_BLOCKED.equals(c.outcome())) {
        throw rejoinBlocked(row, c);
      }
      // 아직 문자가 없음(PENDING) · 횟수 다 씀(FAILED) · 시간 지남(EXPIRED) — 원인증은 그대로
      return new Result(
          row.authRequestId(),
          IDENTITY,
          accountId,
          row.newAccount(),
          null,
          List.of(),
          List.of(),
          VERIFY_IDENTITY,
          now,
          new IdentityStatus(
              c.verificationId(),
              c.state(),
              c.failureReason(),
              c.remainingAttempts(),
              c.expiresAt()));
    }
    Result r =
        Objects.requireNonNull(
            tx.execute(
                status -> {
                  AuthRequestRow locked =
                      customers
                          .lockRequest(authRequestId)
                          .orElseThrow(
                              () ->
                                  PmartException.notFound(
                                      "AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
                  if (MEMBER.equals(locked.state())) {
                    if (!idempotencyKey.equals(locked.completionKey())) {
                      throw PmartException.invalidState("이미 다른 요청으로 로그인을 마쳤습니다.");
                    }
                    return memberResult(
                        locked, openSession(accountId, deviceId, sourceSystem, now), now);
                  }
                  if (!IDENTITY.equals(locked.state())) {
                    throw PmartException.invalidState("휴대폰 확인을 기다리는 로그인 요청이 아닙니다.");
                  }
                  String state = customers.accountState(accountId).orElse("ACTIVE");
                  if (!"ACTIVE".equals(state)) {
                    throw accountNotAvailable(state);
                  }
                  suspensions.check(accountId); // 2026-11-23 CU-7 정지 기한 안이면 403
                  customers.completeIdentity(authRequestId, idempotencyKey, now);
                  customers.markMember(accountId, now);
                  SessionTokens.Tokens t = openSession(accountId, deviceId, sourceSystem, now);
                  publishAuthenticated(accountId, row.provider(), now, identity, profileImageUrl);
                  return memberResult(locked, t, now);
                }));
    log.info("고객 휴대폰 확인 완료: accountId={} new={}", accountId, r.newAccount());
    moveDeviceConsents(accountId, deviceId);
    return r;
  }

  static final String IDENTITY_ALREADY_REGISTERED = "IDENTITY_ALREADY_REGISTERED";
  static final String IDENTITY_REJOIN_BLOCKED = "IDENTITY_REJOIN_BLOCKED";

  private AuthRequestRow identityRequest(String authRequestId, String deviceId) {
    AuthRequestRow row =
        customers
            .request(authRequestId)
            .orElseThrow(() -> PmartException.notFound("AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
    if (!row.deviceId().equals(deviceId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인을 시작한 기기에서만 확인할 수 있습니다.");
    }
    if (row.accountId() == null || row.failureCode() != null || !IDENTITY.equals(row.state())) {
      throw PmartException.invalidState("휴대폰 확인을 기다리는 로그인 요청이 아닙니다(AUTH_IDENTITY 아님).");
    }
    return row;
  }

  /** 로그인한 그 소셜 계정인지 다시 확인(토큰이 없는 단계의 본인 확인). */
  private IdTokenVerifier.Identity reverify(AuthRequestRow row, String providerToken) {
    IdTokenVerifier.Identity identity;
    try {
      identity = verifiers.require(row.provider()).verify(providerToken);
    } catch (IdTokenVerifier.Unavailable e) {
      throw providerUnavailable(e);
    } catch (IdTokenVerifier.Rejected e) {
      log.warn("휴대폰 확인 단계 거절(PROVIDER_REJECTED) provider={}", row.provider());
      throw providerRejected(row.provider());
    }
    if (!Objects.equals(
        row.accountId(),
        customers.linkedAccount(row.provider(), identity.subject()).orElse(null))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인한 계정과 다른 소셜 계정입니다.");
    }
    return identity;
  }

  /** 휴대폰 확인 단계로 넘어감(동의 완료 뒤 — ADR-0074). */
  private static Result identityResult(AuthRequestRow row, Instant now) {
    return new Result(
        row.authRequestId(),
        IDENTITY,
        row.accountId(),
        row.newAccount(),
        null,
        List.of(),
        List.of(),
        VERIFY_IDENTITY,
        now);
  }

  /** 그 번호로 가입한 다른 계정 — 확인을 통과한 본인에게만 그 계정의 제공자·가입일·가린 이메일(계정 ID·번호는 주지 않음). 이번에 만든 계정은 버린다. */
  private PmartException alreadyRegistered(AuthRequestRow row, CustomerIdentityClient.Confirmed c) {
    String existing = c.existingAccountId();
    List<PmartException.Detail> details = new java.util.ArrayList<>();
    if (existing != null) {
      customers
          .provider(existing)
          .ifPresent(p -> details.add(new PmartException.Detail("provider", p)));
      customers
          .memberSince(existing)
          .ifPresent(t -> details.add(new PmartException.Detail("joinedAt", t.toString())));
    }
    if (c.existingEmailMasked() != null) {
      details.add(new PmartException.Detail("emailMasked", c.existingEmailMasked()));
    }
    endIdentity(row, IDENTITY_ALREADY_REGISTERED);
    return new PmartException(
        IDENTITY_ALREADY_REGISTERED,
        HttpStatus.CONFLICT,
        "이 휴대폰 번호로 가입한 계정이 있습니다. 그 계정으로 로그인해 주세요.",
        false,
        details);
  }

  private PmartException rejoinBlocked(AuthRequestRow row, CustomerIdentityClient.Confirmed c) {
    endIdentity(row, IDENTITY_REJOIN_BLOCKED);
    return new PmartException(
        IDENTITY_REJOIN_BLOCKED,
        HttpStatus.CONFLICT,
        "탈퇴한 번호는 한 달 동안 다시 가입할 수 없습니다.",
        false,
        c.rejoinAvailableAt() == null
            ? List.of()
            : List.of(
                new PmartException.Detail("rejoinAvailableAt", c.rejoinAvailableAt().toString())));
  }

  /** 막힌 확인: 원인증을 끝내고, 이번에 만든(회원이 된 적 없는) 계정은 버린다(그 소셜 계정의 다음 로그인은 새 가입). */
  private void endIdentity(AuthRequestRow row, String code) {
    tx.executeWithoutResult(
        status -> {
          customers.failIdentity(row.authRequestId(), code);
          boolean discarded =
              customers.discardIfNeverMember(Objects.requireNonNull(row.accountId()));
          log.info("고객 휴대폰 확인 막힘 {}: accountId={} 버림={}", code, row.accountId(), discarded);
        });
  }

  private static Result memberResult(AuthRequestRow row, SessionTokens.Tokens t, Instant now) {
    return new Result(
        row.authRequestId(),
        MEMBER,
        row.accountId(),
        row.newAccount(),
        t,
        List.of(),
        List.of(),
        "NONE",
        now);
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
    throw providerRejected(provider);
  }

  private void checkRestriction(String deviceId, Instant now) {
    Optional<Instant> restricted = customers.restrictedUntil(deviceId).filter(u -> u.isAfter(now));
    if (restricted.isPresent()) {
      throw restrictedUntil(restricted.get(), now);
    }
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
      String accountId,
      String provider,
      Instant at,
      IdTokenVerifier.Identity identity,
      @Nullable String appPictureUrl) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", accountId);
    payload.put("provider", provider);
    payload.put("authenticatedAt", at.toString());
    String email = identity.email();
    Boolean emailVerified = identity.emailVerified();
    if (email == null && "NAVER".equals(provider)) {
      // 네이버 ID 토큰에는 이메일이 없다 — 교환 때 서버가 네이버 프로필 API 에서 직접 받아 이 sub 에 묶어 둔 값(BR-77).
      // 네이버는 확인 여부를 주지 않아 emailVerified 는 싣지 않는다. 없으면 이메일 없이(customer 의 기존 값은 그대로).
      email = naverEmails.take(identity.subject(), at);
      emailVerified = null;
    }
    putIfPresent(payload, "email", email);
    putIfPresent(payload, "emailVerified", emailVerified);
    putIfPresent(payload, "name", identity.name());
    // 네이버는 ID 토큰에 사진이 없어 앱이 넘긴 주소(0.8.0, ProviderPictureRules — 맞을 때만)
    putIfPresent(
        payload,
        "pictureUrl",
        com.pmart.auth.domain.ProviderPictureRules.pick(
            provider, identity.pictureUrl(), appPictureUrl));
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

  static PmartException providerUnavailable(IdTokenVerifier.Unavailable e) {
    log.warn("소셜 제공자 검증 불가(실패 횟수 제외): {}", e.getMessage());
    return new PmartException(
        "PROVIDER_UNAVAILABLE",
        HttpStatus.SERVICE_UNAVAILABLE,
        "로그인 제공자 인증 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
        true,
        List.of());
  }

  private static PmartException accountNotAvailable(String state) {
    return new PmartException(
        "ACCOUNT_NOT_AVAILABLE",
        HttpStatus.FORBIDDEN,
        "이용할 수 없는 계정입니다(" + state + ").",
        false,
        List.of());
  }

  /** 필수 항목이 빠졌다(422). details 에 빠진 항목 {@code TYPE:version}. */
  private static PmartException requiredMissing(List<String> missing) {
    return new PmartException(
        "REQUIRED_CONSENT_MISSING",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "필수 약관에 모두 동의해야 가입·로그인을 마칠 수 있습니다.",
        false,
        missing.stream().map(m -> new PmartException.Detail(m, "필수 동의 필요")).toList());
  }

  /** 안내 문구에 쓰는 제공자 이름 — GOOGLE "Google"·KAKAO "카카오"·NAVER "네이버", 그 밖 "로그인 제공자". */
  static String providerName(@Nullable String provider) {
    return switch (provider == null ? "" : provider) {
      case "GOOGLE" -> "Google";
      case "KAKAO" -> "카카오";
      case "NAVER" -> "네이버";
      default -> "로그인 제공자";
    };
  }

  static PmartException providerRejected(@Nullable String provider) {
    return new PmartException(
        "PROVIDER_REJECTED",
        HttpStatus.UNAUTHORIZED,
        providerName(provider) + " 인증 결과를 확인할 수 없습니다. 다시 로그인하세요.",
        false,
        List.of());
  }

  static RestrictedException restrictedUntil(Instant until, Instant now) {
    return new RestrictedException(until, Math.max(1, Duration.between(now, until).toSeconds()));
  }
}
