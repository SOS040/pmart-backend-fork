package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.PhoneVerificationRules;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.OctomoClient;
import com.pmart.customer.infrastructure.PhoneVerificationStore;
import com.pmart.customer.infrastructure.PhoneVerificationStore.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 휴대폰 번호 확인(2026-11-19, 계약 0.9.0 — 사용자 "옥토모로"). 고객이 OCTOMO 대표번호로 우리가 준 6자리를 문자로 보내고, 확인 때 OCTOMO 에
 * 그 번호에서 그 본문이 왔는지 묻는다(MO — 우리는 문자를 보내지 않는다). 확인되면 프로필 phone_verified. 번호 소유만 확인한다(이름·나이 아님).
 *
 * <ul>
 *   <li>시작: 프로필 번호(010 11자리)·탈퇴 전·아직 미확인·하루 시작 횟수 안. 같은 Idempotency-Key 는 같은 결과(같은 본문).
 *   <li>확인: OCTOMO 호출은 트랜잭션 밖. 끝난 확인은 다시 부르지 않는다(상태로 멱등). OCTOMO 실패는 시도로 세지 않는다(503).
 * </ul>
 *
 * 번호·본문은 로그에 남기지 않는다.
 */
@Service
public class PhoneVerificationService {

  public static final String OPERATION = "startPhoneVerification";

  private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

  /** 시작 결과(멱등 기록에 남는다). */
  public record Started(
      String verificationId,
      String sendTo,
      String text,
      String expiresAt,
      String phoneMasked,
      int maxAttempts) {}

  public record View(
      String verificationId,
      String state,
      @Nullable String failureReason,
      int remainingAttempts,
      Instant expiresAt,
      String phoneMasked,
      @Nullable Instant verifiedAt) {}

  private final CustomerStore customers;
  private final PhoneVerificationStore store;
  private final OctomoClient octomo;
  private final PhoneVerificationProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;
  private final IdentityService identities;

  public PhoneVerificationService(
      CustomerStore customers,
      PhoneVerificationStore store,
      OctomoClient octomo,
      PhoneVerificationProperties props,
      RequestIdempotency idempotency,
      Clock clock,
      IdentityService identities) {
    this.identities = identities;
    this.customers = customers;
    this.store = store;
    this.octomo = octomo;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  private PhoneVerificationProperties cfg() {
    if (!props.complete()) {
      throw PmartException.configRequired("pmart.customer.phone-verification");
    }
    return props;
  }

  public Started start(String accountId, String requestId, String sourceSystem) {
    PhoneVerificationProperties cfg = cfg();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OPERATION, accountId),
            Map.of(),
            Started.class,
            () -> {
              ProfileRow p =
                  customers
                      .profile(accountId)
                      .orElseThrow(
                          () -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
              if (!ProfileRules.changeable(p.status())) {
                throw PmartException.invalidState("탈퇴 신청 이후에는 번호를 확인할 수 없습니다.");
              }
              String phone = p.phone();
              if (phone == null || phone.isBlank()) {
                throw bad("PHONE_REQUIRED", "확인할 휴대폰 번호가 없습니다. 먼저 번호를 저장하세요.");
              }
              if (!PhoneVerificationRules.supported(phone)) {
                throw bad("PHONE_NOT_SUPPORTED", "010 으로 시작하는 휴대폰 번호만 확인할 수 있습니다.");
              }
              if (p.phoneVerified()) {
                throw PmartException.invalidState("이미 확인된 번호입니다.");
              }
              Instant now = clock.instant();
              if (store.startsSince(accountId, now.minus(Duration.ofDays(1)))
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
              store.start(
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
                      null));
              log.info("휴대폰 번호 확인 시작 {} {}", accountId, id);
              return new Started(
                  id,
                  cfg.sendTo(),
                  code,
                  expiresAt.toString(),
                  java.util.Objects.requireNonNullElse(ProfileRules.maskPhone(phone), ""),
                  cfg.maxAttempts());
            })
        .value();
  }

  public View get(String accountId, String verificationId) {
    PhoneVerificationProperties cfg = cfg();
    return view(own(accountId, verificationId), cfg);
  }

  public View confirm(String accountId, String verificationId) {
    PhoneVerificationProperties cfg = cfg();
    Row r = own(accountId, verificationId);
    if (!PhoneVerificationRules.PENDING.equals(r.state())) {
      return view(r, cfg); // 끝난 확인 — OCTOMO 를 부르지 않는다
    }
    Instant now = clock.instant();
    if (!now.isBefore(r.expiresAt())) {
      store.finish(verificationId, PhoneVerificationRules.EXPIRED, null, now);
      return view(own(accountId, verificationId), cfg);
    }
    ProfileRow p = customers.profile(accountId).orElseThrow();
    String phone = p.phone();
    if (phone == null || !PhoneVerificationRules.phoneHash(phone).equals(r.phoneHash())) {
      store.finish(
          verificationId, PhoneVerificationRules.FAILED, PhoneVerificationRules.PHONE_CHANGED, now);
      return view(own(accountId, verificationId), cfg);
    }
    boolean arrived =
        octomo.exists(cfg.octomoBaseUrl(), cfg.apiKey(), phone, r.code(), cfg.withinMinutes());
    Instant at = clock.instant();
    if (arrived) {
      // 한 번호 한 계정(2026-11-20 ADR-0074): 다른 계정의 확인 번호면 FAILED·PHONE_ALREADY_REGISTERED, 아니면 계정 확인
      // 번호·프로필
      IdentityService.Settled s = identities.settle(verificationId, accountId, phone, false);
      log.info("휴대폰 번호 확인 {} {} → {}", accountId, verificationId, s.outcome());
    } else {
      store.miss(verificationId, cfg.maxAttempts(), at);
    }
    return view(own(accountId, verificationId), cfg);
  }

  private Row own(String accountId, String verificationId) {
    return store
        .find(verificationId)
        .filter(r -> r.accountId().equals(accountId))
        .orElseThrow(
            () -> PmartException.notFound("PHONE_VERIFICATION_NOT_FOUND", "번호 확인을 찾을 수 없습니다."));
  }

  private View view(Row r, PhoneVerificationProperties cfg) {
    String phone = customers.profile(r.accountId()).map(ProfileRow::phone).orElse(null);
    String masked =
        phone != null && PhoneVerificationRules.phoneHash(phone).equals(r.phoneHash())
            ? java.util.Objects.requireNonNullElse(ProfileRules.maskPhone(phone), "")
            : "";
    boolean pendingButExpired =
        PhoneVerificationRules.PENDING.equals(r.state())
            && !clock.instant().isBefore(r.expiresAt());
    return new View(
        r.verificationId(),
        pendingButExpired ? PhoneVerificationRules.EXPIRED : r.state(),
        r.failureReason(),
        PhoneVerificationRules.remaining(cfg.maxAttempts(), r.attempts()),
        r.expiresAt(),
        masked,
        PhoneVerificationRules.VERIFIED.equals(r.state()) ? r.finishedAt() : null);
  }

  private static PmartException bad(String code, String message) {
    return new PmartException(code, HttpStatus.BAD_REQUEST, message, false, List.of());
  }
}
