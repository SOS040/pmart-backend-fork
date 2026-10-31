package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.CustomerStore.ProviderProfile;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
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
  private final ProfileImageService profileImages;

  public ProfileService(CustomerStore store, Clock clock, ProfileImageService profileImages) {
    this.store = store;
    this.clock = clock;
    this.profileImages = profileImages;
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
    // 제공자 프로필(ADR-0024) — 개인정보라 로그에 남기지 않는다
    ProviderProfile profile =
        new ProviderProfile(
            text(p, "name"),
            text(p, "email"),
            p.hasNonNull("emailVerified") ? p.get("emailVerified").asBoolean() : null,
            text(p, "pictureUrl"));
    store.upsertFromAuthentication(
        accountId, p.path("provider").asString("GOOGLE"), at, profile, clock.instant());
    // 사진은 제공자 URL 을 그대로 주지 않고 커밋 뒤 우리 저장소로 사본을 만든다
    profileImages.copyAfterCommit(accountId);
    log.info("고객 인증 반영 {}", accountId);
  }

  private static @Nullable String text(JsonNode p, String field) {
    String v = p.hasNonNull(field) ? p.get(field).asString() : null;
    return v == null || v.isBlank() ? null : v;
  }

  /**
   * OrderConfirmed(order.order.v1) 반영 — inbox 트랜잭션 안. 첫 구매 무료배달을 실제 적용한 주문(payload.firstPurchase)이면
   * 사용 기록을 남겨 이후 first-purchase-checks 가 후보가 아니라고 답하게 한다. 이미 기록이 있으면 그대로.
   */
  public void applyOrderEvent(EventEnvelope e) {
    if (!"OrderConfirmed".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String accountId = p.path("accountId").asString("");
    if (accountId.isBlank() || !p.path("firstPurchase").asBoolean(false)) {
      return;
    }
    String orderId = p.path("orderId").asString(e.aggregateId());
    Instant at =
        p.hasNonNull("confirmedAt")
            ? Instant.parse(p.get("confirmedAt").asString())
            : e.occurredAt();
    boolean marked = store.markFirstPurchaseUsed(accountId, orderId, at);
    log.info("첫 구매 혜택 사용 {} 주문 {} ({})", accountId, orderId, marked ? "기록" : "이미 있음");
  }
}
