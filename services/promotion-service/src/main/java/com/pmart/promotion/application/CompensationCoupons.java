package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.CouponGrantStore;
import com.pmart.promotion.infrastructure.CouponGrantStore.Pending;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 회사 사유 보상 쿠폰(CL-5 배차 불가 취소 → 2,000원 쿠폰, ADR-0082 5단계). order OrderCancelled(cancelReason
 * DISPATCH_UNAVAILABLE)를 받아 주문마다 한 번 기록하고, 설정한 보상 행사({@code
 * pmart.promotion.compensation.dispatch-unavailable-promotion-id} — COUPON·ADMIN·진행 중)에서 한 장 준다. 1인
 * 한도는 보지 않는다(주문마다 보상). 행사가 없거나·진행 중이 아니거나·다 나갔으면 PENDING 으로 두고 주기 작업이 다시 시도한다.
 */
@Service
public class CompensationCoupons {

  public static final String DISPATCH_UNAVAILABLE = "DISPATCH_UNAVAILABLE";
  private static final Logger log = LoggerFactory.getLogger(CompensationCoupons.class);

  private final CouponGrantStore grants;
  private final PromotionStore promotions;
  private final CouponService coupons;
  private final PromotionProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CompensationCoupons(
      CouponGrantStore grants,
      PromotionStore promotions,
      CouponService coupons,
      PromotionProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.grants = grants;
    this.promotions = promotions;
    this.coupons = coupons;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public static boolean wanted(EventEnvelope e) {
    return "OrderCancelled".equals(e.eventType())
        && DISPATCH_UNAVAILABLE.equals(e.payload().path("cancelReason").asString(null));
  }

  /** inbox 트랜잭션 안. 기록하고 바로 한 번 시도. */
  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    String orderId = p.path("orderId").asString(null);
    String accountId = p.path("accountId").asString(null);
    if (orderId == null || accountId == null || accountId.isBlank()) {
      log.warn("배차 불가 취소 {} 에 주문·계정이 없다 — 보상 쿠폰 없음", e.eventId());
      return;
    }
    if (grants.recordCompensation(orderId, accountId, DISPATCH_UNAVAILABLE, clock.instant())) {
      tryIssue(new Pending(orderId, accountId, DISPATCH_UNAVAILABLE));
    }
  }

  @Scheduled(
      fixedDelayString = "${pmart.promotion.compensation.retry-interval:PT1M}",
      initialDelayString = "${pmart.promotion.compensation.retry-initial-delay:PT30S}")
  public void retryPending() {
    try {
      Integer n =
          tx.execute(
              s -> {
                List<Pending> pending = grants.lockPending(100);
                int issued = 0;
                for (Pending x : pending) {
                  issued += tryIssue(x) ? 1 : 0;
                }
                return issued;
              });
      if (n != null && n > 0) {
        log.info("미뤄 둔 보상 쿠폰 {}장 발급", n);
      }
    } catch (RuntimeException ex) {
      log.warn("보상 쿠폰 다시 시도 실패 — 다음 주기에", ex);
    }
  }

  /** 트랜잭션 안. 줬으면 true. */
  boolean tryIssue(Pending x) {
    Instant now = clock.instant();
    String promotionId = props.dispatchUnavailablePromotionId();
    PromotionRow promo = promotionId == null ? null : promotions.lock(promotionId).orElse(null);
    String why = unusable(promo, now);
    if (why != null) {
      log.debug("보상 쿠폰 대기({}) 주문 {}", why, x.orderId());
      return false;
    }
    PromotionRow p = java.util.Objects.requireNonNull(promo);
    String r =
        coupons.issue(p, x.accountId(), "ADMIN", "system", "배차 불가 보상 · 주문 " + x.orderId(), true);
    if (r.startsWith("!")) {
      log.warn("보상 쿠폰 행사 {} 가 다 나갔다 — 주문 {} 대기", p.promotionId(), x.orderId());
      return false;
    }
    grants.markIssued(x.orderId(), x.reason(), p.promotionId(), r, now);
    return true;
  }

  static @Nullable String unusable(@Nullable PromotionRow p, Instant now) {
    if (p == null) {
      return "NOT_CONFIGURED";
    }
    if (!"COUPON".equals(p.kind()) || !"ADMIN".equals(p.issueMode())) {
      return "NOT_ADMIN_COUPON";
    }
    if (!"RUNNING"
        .equals(PromotionRules.phase(p.state(), p.startsAt(), p.endsAt(), p.endedAt(), now))) {
      return "NOT_RUNNING";
    }
    return null;
  }
}
