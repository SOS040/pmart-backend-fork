package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.SessionStore;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 고객 정지(2026-11-23 CU-7 — 사용자 "정지 = 주문·로그인까지 막기", 7일 자동 해제, 영구 정지 없음). customer {@code
 * CustomerRestricted}(commerce.customer.v1)를 받아 {@code customer_account.suspended_until} 사본을 두고:
 * SUSPENDED 면 모든 세션을 끝내고 그 전에 나간 access token 을 막는다(SubjectBlocked). 기한 안의 로그인·토큰 연장은 403
 * ACCOUNT_SUSPENDED(details until). 이용제한 (RESTRICTED)·풀기(NONE)는 사본만 지운다(로그인 막지 않음 — 문의·리뷰는
 * case·review 가 막는다). inbox 트랜잭션 안.
 */
@Service
public class CustomerSuspensions {

  public static final String EVENT = "CustomerRestricted";
  private static final Logger log = LoggerFactory.getLogger(CustomerSuspensions.class);

  private final CustomerStore customers;
  private final SessionStore sessions;
  private final SessionBlocks blocks;
  private final Clock clock;

  public CustomerSuspensions(
      CustomerStore customers, SessionStore sessions, SessionBlocks blocks, Clock clock) {
    this.customers = customers;
    this.sessions = sessions;
    this.blocks = blocks;
    this.clock = clock;
  }

  public void onRestricted(EventEnvelope e) {
    String accountId = e.payload().path("accountId").asString("");
    if (accountId.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    Instant until =
        "SUSPENDED".equals(e.payload().path("restriction").asString(""))
            ? instant(e.payload().path("until").asString(""))
            : null;
    boolean suspended = until != null && until.isAfter(now);
    if (!customers.applyRestriction(accountId, suspended ? until : null, e.aggregateVersion())) {
      log.info("고객 제한 사건 생략(auth 에 없는 계정·옛 사건) eventId={}", e.eventId());
      return;
    }
    if (suspended) {
      sessions.revokeAllSessions(accountId, "SUSPENDED", now);
      blocks.block(
          accountId,
          null,
          CustomerSocialLoginService.TYPE,
          now,
          "ACCOUNT_RESTRICTED", // SubjectBlocked 계약 사유 — 고객 정지
          new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "auth-service"));
      log.info("고객 정지 반영 — 세션 종료 eventId={}", e.eventId());
    }
  }

  /** 기한 안의 정지면 403 ACCOUNT_SUSPENDED. */
  public void check(String accountId) {
    Instant now = clock.instant();
    customers
        .suspendedUntil(accountId)
        .filter(u -> u.isAfter(now))
        .ifPresent(
            u -> {
              throw suspended(u);
            });
  }

  public boolean suspended(String accountId) {
    Instant now = clock.instant();
    return customers.suspendedUntil(accountId).filter(u -> u.isAfter(now)).isPresent();
  }

  static PmartException suspended(Instant until) {
    String text =
        DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
            .format(until.atZone(ZoneId.of("Asia/Seoul")));
    return new PmartException(
        "ACCOUNT_SUSPENDED",
        HttpStatus.FORBIDDEN,
        "계정이 7일 정지되었어요. " + text + "까지 로그인할 수 없어요.",
        false,
        List.of(new PmartException.Detail("until", until.toString())));
  }

  private static @Nullable Instant instant(String s) {
    try {
      return s.isBlank() ? null : Instant.parse(s);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }
}
