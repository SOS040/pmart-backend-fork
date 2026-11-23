package com.pmart.cases.application;

import com.pmart.cases.infrastructure.CustomerRestrictionStore;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 고객 이용제한·정지(2026-11-23 CU-7 — 사용자 "이용제한 = 문의·리뷰 작성 막기, 정지 = 주문·로그인까지"). customer {@code
 * CustomerRestricted}(commerce.customer.v1) 사본 {@code customer_restriction_copy} — 기한 안의
 * RESTRICTED·SUSPENDED 고객은 새 문의 403 ACCOUNT_RESTRICTED(details until). 사본이 늦거나 없으면 막지 않는다(정본은
 * customer).
 */
@Service
public class CustomerRestrictions {

  public static final String EVENT = "CustomerRestricted";

  private final CustomerRestrictionStore store;
  private final Clock clock;

  public CustomerRestrictions(CustomerRestrictionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** inbox 트랜잭션 안 — 더 새 차수만. */
  public void onEvent(EventEnvelope e) {
    String accountId = e.payload().path("accountId").asString("");
    String restriction = e.payload().path("restriction").asString("");
    if (accountId.isBlank() || restriction.isBlank()) {
      return;
    }
    Instant until = instant(e.payload().path("until").asString(""));
    store.upsert(accountId, restriction, until, e.aggregateVersion());
  }

  /** 기한 안의 제한이면 403. */
  public void checkCanWrite(String accountId) {
    store
        .activeUntil(accountId, clock.instant())
        .ifPresent(
            u -> {
              throw restricted(u);
            });
  }

  private static PmartException restricted(Instant u) {
    return new PmartException(
        "ACCOUNT_RESTRICTED",
        HttpStatus.FORBIDDEN,
        "운영 정책 위반으로 "
            + DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
                .format(u.atZone(ZoneId.of("Asia/Seoul")))
            + "까지 문의를 쓸 수 없어요.",
        false,
        List.of(new PmartException.Detail("until", u.toString())));
  }

  private static @Nullable Instant instant(String s) {
    try {
      return s.isBlank() ? null : Instant.parse(s);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }
}
