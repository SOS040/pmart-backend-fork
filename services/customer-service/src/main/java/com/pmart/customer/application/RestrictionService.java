package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.RestrictionRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.RestrictionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 계정 제한(계약 0.16.0 CU-7, 2026-11-23 사용자 결정 — 이용제한 = 문의·리뷰 작성, 정지 = 주문·로그인까지, 7일 자동 해제, 영구 정지 없음).
 * 거는 길: case {@code CustomerProfanityDetected} 자동 7일 정지(찾은 시각부터), 직원(CS_AGENT·OPERATOR_ADMIN)이 사유를
 * 적고 걸기·풀기. 바뀔 때마다 {@code CustomerRestricted}(commerce.customer.v1 — auth·case·notification). 사유는
 * 로그·사건에 넣지 않는다.
 */
@Service
public class RestrictionService {

  public static final String EVENT = "CustomerRestricted";
  static final String PROFANITY = "CustomerProfanityDetected";
  static final String SYSTEM_ACTOR = "system:case-profanity";
  static final String AUTO_REASON = "문의에 위협·거친 말 — 자동 7일 정지";
  private static final Logger log = LoggerFactory.getLogger(RestrictionService.class);

  public record History(ProfileRow customer, List<RestrictionStore.Record> items) {}

  public record Result(String recordId) {}

  private final CustomerStore customers;
  private final RestrictionStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;
  private final Duration period;

  public RestrictionService(
      CustomerStore customers,
      RestrictionStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock,
      @Value("${pmart.customer.restriction.period:P7D}") Duration period) {
    this.customers = customers;
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
    this.period = period;
  }

  public Instant now() {
    return clock.instant();
  }

  public History history(String customerId) {
    StaffCustomerService.requireStaff();
    ProfileRow c = customer(customerId);
    return new History(c, store.history(customerId, 50));
  }

  /** 직원 걸기 — RESTRICTED·SUSPENDED, 지금부터 period. */
  public Result restrict(
      String customerId,
      String restriction,
      @Nullable String reason,
      String requestId,
      String sourceSystem) {
    PmartPrincipal p = StaffCustomerService.requireStaff();
    if (!RestrictionRules.RESTRICTED.equals(restriction)
        && !RestrictionRules.SUSPENDED.equals(restriction)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "RESTRICTED·SUSPENDED 중 하나를 고르세요.");
    }
    String why = reason(reason);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("restriction", restriction);
    body.put("reason", why);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "restrictCustomer:" + customerId, p.subject()),
            body,
            Result.class,
            () -> {
              Instant now = clock.instant();
              ProfileRow c = lock(customerId);
              if ("WITHDRAW_COMPLETED".equals(c.status())) {
                throw PmartException.invalidState("탈퇴한 고객입니다.");
              }
              String id = "RST-" + UUID.randomUUID();
              apply(
                  c,
                  restriction,
                  now.plus(period),
                  "STAFF",
                  why,
                  p.subject(),
                  id,
                  now,
                  EventEnvelope.ActorType.OPERATOR);
              return new Result(id);
            })
        .value();
  }

  /** 직원 풀기(오인식 등). */
  public Result release(
      String customerId, @Nullable String reason, String requestId, String sourceSystem) {
    PmartPrincipal p = StaffCustomerService.requireStaff();
    String why = reason(reason);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", why);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "releaseCustomer:" + customerId, p.subject()),
            body,
            Result.class,
            () -> {
              Instant now = clock.instant();
              ProfileRow c = lock(customerId);
              if (RestrictionRules.NONE.equals(c.restrictionAt(now))) {
                throw new PmartException(
                    "NOT_RESTRICTED", HttpStatus.CONFLICT, "제한 중인 고객이 아닙니다.", false, List.of());
              }
              String id = "RST-" + UUID.randomUUID();
              apply(
                  c,
                  RestrictionRules.NONE,
                  null,
                  "STAFF",
                  why,
                  p.subject(),
                  id,
                  now,
                  EventEnvelope.ActorType.OPERATOR);
              return new Result(id);
            })
        .value();
  }

  /** case(platform.case.v1) 구독 — inbox 트랜잭션 안. 정지 단어 → 7일 정지. 그 밖 사건은 무시. */
  public void onCaseEvent(EventEnvelope e) {
    if (!PROFANITY.equals(e.eventType())) {
      return;
    }
    JsonNode body = e.payload();
    String accountId = body.path("customerId").asString("");
    if (accountId.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    Instant until = instant(body.path("detectedAt").asString(""), now).plus(period);
    if (!until.isAfter(now)) {
      log.info("정지 단어 사건 생략 reason=expired eventId={}", e.eventId());
      return;
    }
    ProfileRow c = customers.lockProfile(accountId).orElse(null);
    if (c == null || !"ACTIVE".equals(c.status()) && !"WITHDRAW_PENDING".equals(c.status())) {
      log.info("정지 단어 사건 생략 reason=no_customer eventId={}", e.eventId());
      return;
    }
    if (!RestrictionRules.autoSuspendNeeded(
        c.restrictionAt(now), c.restrictionUntilAt(now), until)) {
      log.info("정지 단어 사건 생략 reason=already_suspended eventId={}", e.eventId());
      return;
    }
    apply(
        c,
        RestrictionRules.SUSPENDED,
        until,
        "AUTO_PROFANITY",
        AUTO_REASON,
        SYSTEM_ACTOR,
        "profanity:" + e.eventId(),
        now,
        EventEnvelope.ActorType.SYSTEM);
  }

  private void apply(
      ProfileRow c,
      String restriction,
      @Nullable Instant until,
      String source,
      String reason,
      String actor,
      String recordId,
      Instant now,
      EventEnvelope.ActorType actorType) {
    String previous = c.restrictionAt(now);
    if (!store.insert(
        new RestrictionStore.Record(
            recordId, c.accountId(), restriction, previous, until, source, reason, actor, now))) {
      return; // 같은 사건 재전달
    }
    int version = store.set(c.accountId(), restriction, until, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", c.accountId());
    payload.put("restriction", restriction);
    payload.put("previous", previous);
    payload.put("from", now.toString());
    payload.put("until", until == null ? null : until.toString());
    payload.put("source", source);
    events.publish(
        codec.create(
            events.sourceSystem(),
            EVENT,
            1,
            "CustomerRestriction",
            c.accountId(),
            version,
            new EventEnvelope.Actor(actorType, actor),
            EventEnvelope.EventSource.REAL,
            payload),
        AddressService.TOPIC);
    log.info("고객 계정 제한 {} → {} source={} record={}", previous, restriction, source, recordId);
  }

  private ProfileRow customer(String id) {
    return customers
        .profile(id)
        .orElseThrow(() -> PmartException.notFound("CUSTOMER_NOT_FOUND", "고객이 없습니다."));
  }

  private ProfileRow lock(String id) {
    return customers
        .lockProfile(id)
        .orElseThrow(() -> PmartException.notFound("CUSTOMER_NOT_FOUND", "고객이 없습니다."));
  }

  private static String reason(@Nullable String reason) {
    String r = reason == null ? "" : reason.strip();
    if (r.length() < 2 || r.length() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유를 2~200자로 적어 주세요.");
    }
    return r;
  }

  private static Instant instant(String s, Instant fallback) {
    try {
      return s.isBlank() ? fallback : Instant.parse(s);
    } catch (DateTimeParseException ex) {
      return fallback;
    }
  }
}
