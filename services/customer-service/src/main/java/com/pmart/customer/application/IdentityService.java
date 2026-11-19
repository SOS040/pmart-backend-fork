package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.PhoneVerificationRules;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.IdentityStore;
import com.pmart.customer.infrastructure.OctomoClient;
import com.pmart.customer.infrastructure.PhoneVerificationStore;
import com.pmart.customer.infrastructure.PhoneVerificationStore.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입·로그인 휴대폰 확인과 한 번호 한 계정(2026-11-20 ADR-0074 — 고객 BR-64). auth-service 가 토큰 없는 로그인 단계에서 내부 API 로
 * 부른다. 확인 방식은 0.9.0 과 같다(고객이 OCTOMO 대표번호로 숫자를 보냄 — MO). 다른 점: 번호를 본문으로 받고(프로필이 없을 수 있음), 문자가 확인된 뒤
 * 번호 규칙을 본다 — 다른 유효 계정의 번호면 IDENTITY_ALREADY_REGISTERED, 탈퇴로 풀린 지 rejoin-block 안이면
 * IDENTITY_REJOIN_BLOCKED, 아니면 계정의 확인 번호로 남기고 프로필 phone·phoneVerified.
 *
 * <p>규칙 판정(번호 확정)은 /me 확인({@link PhoneVerificationService})도 같이 쓴다({@link #settle}). 번호·본문은 로그에 남기지
 * 않는다.
 */
@Service
public class IdentityService {

  public static final String START_OPERATION = "startIdentityVerification";
  public static final String ALREADY_REGISTERED = "IDENTITY_ALREADY_REGISTERED";
  public static final String REJOIN_BLOCKED = "IDENTITY_REJOIN_BLOCKED";

  private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

  /** 번호 확정 결과. */
  public record Settled(
      String outcome, @Nullable String existingAccountId, @Nullable Instant rejoinAvailableAt) {
    static Settled verified() {
      return new Settled("VERIFIED", null, null);
    }
  }

  /** 확인 결과(내부 API). outcome 은 문자가 확인된 뒤에만. */
  public record Result(
      String verificationId,
      String state,
      @Nullable String failureReason,
      int remainingAttempts,
      Instant expiresAt,
      @Nullable Instant verifiedAt,
      @Nullable String outcome,
      @Nullable String existingAccountId,
      @Nullable String existingEmailMasked,
      @Nullable Instant rejoinAvailableAt) {}

  private final CustomerStore customers;
  private final PhoneVerificationStore verifications;
  private final IdentityStore identities;
  private final OctomoClient octomo;
  private final PhoneVerificationProperties props;
  private final IdentityProperties identityProps;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final Clock clock;

  public IdentityService(
      CustomerStore customers,
      PhoneVerificationStore verifications,
      IdentityStore identities,
      OctomoClient octomo,
      PhoneVerificationProperties props,
      IdentityProperties identityProps,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      Clock clock) {
    this.customers = customers;
    this.verifications = verifications;
    this.identities = identities;
    this.octomo = octomo;
    this.props = props;
    this.identityProps = identityProps;
    this.idempotency = idempotency;
    this.tx = tx;
    this.clock = clock;
  }

  /** 계정의 유효한 확인 시각(없으면 빈 값 — 가입 전 계정 포함). */
  public Optional<Instant> verifiedAt(String accountId) {
    return identities.verifiedAt(accountId);
  }

  /** 가입 단계 확인 시작 — 번호를 본문으로. 같은 Idempotency-Key 는 같은 결과. */
  public PhoneVerificationService.Started start(
      String accountId, String phoneInput, String requestId, String sourceSystem) {
    PhoneVerificationProperties cfg = cfg();
    requireIdentityConfig();
    String phone = ProfileRules.normalizePhone(phoneInput).orElse(null);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, START_OPERATION, accountId),
            Map.of("phoneHash", phone == null ? "" : PhoneVerificationRules.phoneHash(phone)),
            PhoneVerificationService.Started.class,
            () -> {
              if (phone == null || !PhoneVerificationRules.supported(phone)) {
                throw new PmartException(
                    "PHONE_NOT_SUPPORTED",
                    HttpStatus.BAD_REQUEST,
                    "010 으로 시작하는 휴대폰 번호(11자리)만 확인할 수 있습니다.",
                    false,
                    List.of());
              }
              if (identities.verifiedAt(accountId).isPresent()) {
                throw new PmartException(
                    "IDENTITY_ALREADY_VERIFIED",
                    HttpStatus.CONFLICT,
                    "이미 휴대폰 확인을 마친 계정입니다.",
                    false,
                    List.of());
              }
              Instant now = clock.instant();
              if (verifications.startsSince(accountId, now.minus(Duration.ofDays(1)))
                  >= cfg.maxStartsPerDay()) {
                throw new PmartException(
                    "TOO_MANY_VERIFICATIONS",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "오늘은 번호 확인을 더 시작할 수 없습니다. 내일 다시 시도하세요.",
                    false,
                    List.of());
              }
              String id = "PV-" + UUID.randomUUID();
              String code = PhoneVerificationRules.newCode();
              Instant expiresAt = now.plus(cfg.ttl());
              verifications.start(
                  new Row(
                      id,
                      accountId,
                      PhoneVerificationRules.phoneHash(phone),
                      code,
                      PhoneVerificationRules.PENDING,
                      0,
                      null,
                      expiresAt,
                      now,
                      null),
                  phone);
              log.info("가입 단계 휴대폰 확인 시작 {} {}", accountId, id);
              return new PhoneVerificationService.Started(
                  id,
                  cfg.sendTo(),
                  code,
                  expiresAt.toString(),
                  Objects.requireNonNullElse(ProfileRules.maskPhone(phone), ""),
                  cfg.maxAttempts());
            })
        .value();
  }

  /** 가입 단계 확인 — OCTOMO 는 트랜잭션 밖, 끝난 확인은 다시 부르지 않는다. */
  public Result confirm(String accountId, String verificationId) {
    PhoneVerificationProperties cfg = cfg();
    requireIdentityConfig();
    Row r = own(accountId, verificationId);
    String phone =
        verifications
            .phone(verificationId)
            .orElseThrow(
                () ->
                    PmartException.notFound("PHONE_VERIFICATION_NOT_FOUND", "가입 단계 번호 확인이 아닙니다."));
    if (PhoneVerificationRules.PENDING.equals(r.state())) {
      Instant now = clock.instant();
      if (!now.isBefore(r.expiresAt())) {
        verifications.finish(verificationId, PhoneVerificationRules.EXPIRED, null, now);
      } else if (octomo.exists(
          cfg.octomoBaseUrl(), cfg.apiKey(), phone, r.code(), cfg.withinMinutes())) {
        Settled s = settle(verificationId, accountId, phone, true);
        log.info("가입 단계 휴대폰 확인 {} {} → {}", accountId, verificationId, s.outcome());
      } else {
        verifications.miss(verificationId, cfg.maxAttempts(), clock.instant());
      }
    }
    return result(own(accountId, verificationId), cfg);
  }

  /**
   * 문자가 확인된 번호를 확정한다(한 트랜잭션). 다른 유효 계정의 번호·탈퇴 막기 기간이면 확인을 FAILED 로 끝낸다(가입 단계는 그 사유, /me 확인은
   * PHONE_ALREADY_REGISTERED — 안내 없음). 동시에 같은 번호가 확정되면 고유 색인이 막고 한 번 더 판정한다.
   */
  public Settled settle(String verificationId, String accountId, String phone, boolean signup) {
    Duration block = requireIdentityConfig();
    try {
      return Objects.requireNonNull(
          tx.execute(st -> settleOnce(verificationId, accountId, phone, signup, block)));
    } catch (DuplicateKeyException race) {
      return Objects.requireNonNull(
          tx.execute(st -> settleOnce(verificationId, accountId, phone, signup, block)));
    }
  }

  private Settled settleOnce(
      String verificationId, String accountId, String phone, boolean signup, Duration block) {
    Instant at = clock.instant();
    String hash = PhoneVerificationRules.phoneHash(phone);
    Optional<String> owner = identities.activeOwner(hash).filter(o -> !o.equals(accountId));
    if (owner.isPresent()) {
      verifications.finish(
          verificationId,
          PhoneVerificationRules.FAILED,
          signup ? ALREADY_REGISTERED : PhoneVerificationRules.PHONE_ALREADY_REGISTERED,
          at);
      return new Settled(ALREADY_REGISTERED, owner.get(), null);
    }
    Optional<Instant> released = identities.lastReleasedByOther(hash, accountId);
    if (released.isPresent() && released.get().plus(block).isAfter(at)) {
      verifications.finish(
          verificationId,
          PhoneVerificationRules.FAILED,
          signup ? REJOIN_BLOCKED : PhoneVerificationRules.PHONE_ALREADY_REGISTERED,
          at);
      return new Settled(REJOIN_BLOCKED, null, released.get().plus(block));
    }
    if (!verifications.markVerified(verificationId, at)) {
      return Settled.verified(); // 이미 끝난 확인(동시 요청) — 결과는 확인 행이 말한다
    }
    identities.claim(accountId, hash, phone, at);
    customers.ensureProfile(accountId, at);
    customers.setVerifiedPhone(accountId, phone, at);
    return Settled.verified();
  }

  private Result result(Row r, PhoneVerificationProperties cfg) {
    boolean pendingButExpired =
        PhoneVerificationRules.PENDING.equals(r.state())
            && !clock.instant().isBefore(r.expiresAt());
    String state = pendingButExpired ? PhoneVerificationRules.EXPIRED : r.state();
    String outcome = null;
    String existing = null;
    String email = null;
    Instant rejoin = null;
    if (PhoneVerificationRules.VERIFIED.equals(state)) {
      outcome = "VERIFIED";
    } else if (ALREADY_REGISTERED.equals(r.failureReason())) {
      outcome = ALREADY_REGISTERED;
      existing = identities.activeOwner(r.phoneHash()).orElse(null);
      email =
          existing == null
              ? null
              : customers
                  .profile(existing)
                  .map(p -> ProfileRules.maskEmail(p.email()))
                  .orElse(null);
    } else if (REJOIN_BLOCKED.equals(r.failureReason())) {
      outcome = REJOIN_BLOCKED;
      rejoin =
          identities
              .lastReleasedByOther(r.phoneHash(), r.accountId())
              .map(t -> t.plus(Objects.requireNonNull(identityProps.rejoinBlock())))
              .orElse(null);
    }
    return new Result(
        r.verificationId(),
        state,
        r.failureReason(),
        PhoneVerificationRules.remaining(cfg.maxAttempts(), r.attempts()),
        r.expiresAt(),
        PhoneVerificationRules.VERIFIED.equals(state) ? r.finishedAt() : null,
        outcome,
        existing,
        email,
        rejoin);
  }

  private Row own(String accountId, String verificationId) {
    return verifications
        .find(verificationId)
        .filter(x -> x.accountId().equals(accountId))
        .orElseThrow(
            () -> PmartException.notFound("PHONE_VERIFICATION_NOT_FOUND", "번호 확인을 찾을 수 없습니다."));
  }

  private PhoneVerificationProperties cfg() {
    if (!props.complete()) {
      throw PmartException.configRequired("pmart.customer.phone-verification");
    }
    return props;
  }

  private Duration requireIdentityConfig() {
    if (!identityProps.complete()) {
      throw PmartException.configRequired("pmart.customer.identity.rejoin-block");
    }
    return Objects.requireNonNull(identityProps.rejoinBlock());
  }
}
