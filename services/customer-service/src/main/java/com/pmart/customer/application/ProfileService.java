package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 프로필·이용 상태. 계정은 auth 의 CustomerAuthenticated 로 생기고(인증 성공 ≠ 구매 자격), 이벤트보다 API 가 먼저 와도 프로필을 만든다.
 *
 * <p>약관 동의는 사용자 결정(2026-10-30, ADR-0019)으로 아직 받지 않는다 — 구매 가능 여부는 계정 상태(ACTIVE)로만 판단하고
 * requiredConsentOutdated 는 false. 동의 기능을 켜면 여기서 함께 본다.
 */
@Service
public class ProfileService {

  private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

  private final CustomerStore store;
  private final Clock clock;

  public ProfileService(CustomerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public ProfileRow me(String accountId) {
    store.ensureProfile(accountId, clock.instant());
    return profile(accountId);
  }

  public ProfileRow profile(String accountId) {
    return store
        .profile(accountId)
        .orElseThrow(() -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
  }

  /** 첫 구매 무료배달 후보 — 혜택 사용 기록이 없으면 true. 자격 점유는 order 가 한다. */
  public boolean firstPurchaseCandidate(String accountId) {
    return !store.firstPurchaseUsed(accountId);
  }

  public boolean purchasable(ProfileRow p) {
    return "ACTIVE".equals(p.status());
  }

  /** CustomerAuthenticated(platform.auth.v1) 반영 — inbox 트랜잭션 안. 다른 auth 이벤트는 무시. */
  public void applyAuthEvent(EventEnvelope e) {
    if (!"CustomerAuthenticated".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String accountId = p.path("accountId").asString("");
    if (accountId.isBlank()) {
      return;
    }
    Instant at =
        p.hasNonNull("authenticatedAt")
            ? Instant.parse(p.get("authenticatedAt").asString())
            : e.occurredAt();
    store.upsertFromAuthentication(
        accountId, p.path("provider").asString("GOOGLE"), at, clock.instant());
    log.info("고객 인증 반영 {}", accountId);
  }
}
