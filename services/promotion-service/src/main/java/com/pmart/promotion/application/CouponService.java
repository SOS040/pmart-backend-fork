package com.pmart.promotion.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.CouponRow;
import com.pmart.promotion.infrastructure.PromotionStore.HoldRow;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 쿠폰 발급(내려받기·지정 발급·가입 축하)·내 쿠폰함·할인 계산·점유·사용 확정·해제·복원(ADR-0082).
 *
 * <p>발급은 행사 행을 잠가 총 수량·1인 한도를 지킨다(마지막 1장은 한 명만). 점유는 쿠폰 행을 잠가 한 쿠폰을 한 주문만 쓰게 한다. 전체 취소 복원은 원래 사용
 * 기한이 남았을 때만 다시 쓸 수 있다(연장·새 발급 없음).
 */
@Service
public class CouponService {

  public static final List<String> GRANTERS = List.of("MARKETING", "CS_AGENT", "OPERATOR_ADMIN");

  /** 쿠폰 + 그 행사(이름·할인 표시). status = AVAILABLE·USED·EXPIRED. */
  public record CouponView(CouponRow coupon, PromotionRow promotion, String status) {}

  public record Campaign(PromotionRow promotion, int myIssuedCount) {}

  public record Quote(
      String couponId,
      boolean applicable,
      @Nullable String reason,
      long discountAmount,
      @Nullable String promotionId,
      @Nullable String name) {}

  public record GrantResult(String accountId, String result, @Nullable String couponId) {}

  private final PromotionStore store;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final PromotionProperties props;
  private final Clock clock;
  private final com.pmart.common.messaging.EventPublisher events;
  private final com.pmart.common.messaging.EventEnvelopeCodec codec;

  /** 쿠폰 발급 알림 사건 토픽(CouponIssued — notification 구독). */
  public static final String COUPON_TOPIC = "commerce.coupon.v1";

  public CouponService(
      PromotionStore store,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      PromotionProperties props,
      Clock clock,
      com.pmart.common.messaging.EventPublisher events,
      com.pmart.common.messaging.EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
    this.store = store;
    this.idempotency = idempotency;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  private static PmartPrincipal customer() {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null || who.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인한 고객만 쿠폰을 받을 수 있습니다.");
    }
    return who;
  }

  // ------------------------------------------------------------------ 고객

  public List<Campaign> campaigns() {
    PmartPrincipal who = customer();
    Map<String, Integer> mine = store.issuedToByPromotion(who.subject());
    return store.runningCoupons("DOWNLOAD", now()).stream()
        .map(p -> new Campaign(p, mine.getOrDefault(p.promotionId(), 0)))
        .toList();
  }

  public CouponView download(String promotionId, String requestId, String sourceSystem) {
    PmartPrincipal who = customer();
    String couponId =
        idempotency
            .execute(
                new RequestKey(
                    sourceSystem, requestId, "downloadCoupon:" + promotionId, who.subject()),
                promotionId,
                String.class,
                () -> {
                  PromotionRow p = lockedRunning(promotionId);
                  if (!"DOWNLOAD".equals(p.issueMode())) {
                    throw new PmartException(ErrorCode.UNSUPPORTED_ACTION, "내려받는 쿠폰 행사가 아닙니다.");
                  }
                  String r = issue(p, who.subject(), "DOWNLOAD", null, null);
                  if (r.startsWith("!")) {
                    throw unavailable(r.substring(1));
                  }
                  return r;
                })
            .value();
    return view(store.findCoupon(couponId).orElseThrow());
  }

  public List<CouponView> myCoupons(@Nullable String status) {
    PmartPrincipal who = customer();
    List<CouponView> out = new ArrayList<>();
    Map<String, PromotionRow> promos = new LinkedHashMap<>();
    for (CouponRow c : store.couponsOf(who.subject())) {
      PromotionRow p = promos.computeIfAbsent(c.promotionId(), id -> store.find(id).orElseThrow());
      CouponView v = new CouponView(c, p, status(c));
      if (status == null || status.equals(v.status())) {
        out.add(v);
      }
    }
    if ("AVAILABLE".equals(status)) {
      out.sort(Comparator.comparing((CouponView v) -> v.coupon().validUntil()));
    }
    return out;
  }

  // ------------------------------------------------------------------ 직원 지정 발급

  public List<GrantResult> grant(
      String promotionId,
      List<String> accountIds,
      String reason,
      String requestId,
      String sourceSystem) {
    PmartPrincipal who = PromotionService.require(GRANTERS, "쿠폰 지정 발급");
    GrantResult[] results =
        idempotency
            .execute(
                new RequestKey(
                    sourceSystem, requestId, "grantCoupons:" + promotionId, who.subject()),
                Map.of("accountIds", accountIds, "reason", reason),
                GrantResult[].class,
                () -> {
                  PromotionRow p = lockedRunning(promotionId);
                  if (!"ADMIN".equals(p.issueMode())) {
                    throw new PmartException(
                        ErrorCode.UNSUPPORTED_ACTION, "지정 발급 행사(발급 방식 ADMIN)가 아닙니다.");
                  }
                  List<GrantResult> out = new ArrayList<>();
                  for (String acc : accountIds.stream().distinct().toList()) {
                    String r = issue(p, acc, "ADMIN", who.subject(), reason);
                    out.add(
                        r.startsWith("!")
                            ? new GrantResult(
                                acc, "!LIMIT".equals(r) ? "SKIPPED_LIMIT" : "SOLD_OUT", null)
                            : new GrantResult(acc, "ISSUED", r));
                  }
                  return out.toArray(GrantResult[]::new);
                })
            .value();
    return List.of(results);
  }

  /** 한 장 발급(행사 행은 호출자가 잠금). 성공이면 쿠폰 ID, 아니면 "!LIMIT"(1인 한도)·"!SOLD_OUT"(총 수량). */
  String issue(
      PromotionRow p,
      String accountId,
      String channel,
      @Nullable String issuedBy,
      @Nullable String reason) {
    return issue(p, accountId, channel, issuedBy, reason, false, null);
  }

  /** perCustomerLimitIgnored = 회사 사유 보상(주문마다 한 번 — 1인 한도로 막지 않는다, 중복은 호출자가 주문으로 막음). */
  String issue(
      PromotionRow p,
      String accountId,
      String channel,
      @Nullable String issuedBy,
      @Nullable String reason,
      boolean perCustomerLimitIgnored,
      @Nullable String orderId) {
    int limit = p.perCustomerLimit() == null ? 1 : p.perCustomerLimit();
    if (!perCustomerLimitIgnored && store.issuedTo(p.promotionId(), accountId) >= limit) {
      return "!LIMIT";
    }
    if (!store.incrementIssued(p.promotionId())) {
      return "!SOLD_OUT";
    }
    Instant at = now();
    String id = "CPN-" + UUID.randomUUID();
    PromotionRules.CouponPolicy policy =
        java.util.Objects.requireNonNull(PromotionInputs.coupon(p));
    store.insertCoupon(
        new CouponRow(
            id,
            p.promotionId(),
            accountId,
            "AVAILABLE",
            at,
            PromotionRules.validUntil(policy, at, props.zoneOrDefault()),
            channel,
            issuedBy,
            reason,
            null,
            null),
        at);
    // 서버가 준 쿠폰(가입 축하·보상·직원 지정)은 알린다 — 앱에서 직접 받은 것(DOWNLOAD)은 고객이 이미 안다(2026-11-21 5단계)
    if (!"DOWNLOAD".equals(channel)) {
      Instant validUntil = PromotionRules.validUntil(policy, at, props.zoneOrDefault());
      java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("couponId", id);
      payload.put("accountId", accountId);
      payload.put("promotionId", p.promotionId());
      payload.put("promotionName", p.name());
      payload.put("channel", channel);
      payload.put("reason", orderId == null ? null : "DISPATCH_UNAVAILABLE");
      payload.put("orderId", orderId);
      payload.put("discountType", p.discountType());
      payload.put("discountValue", p.discountValue());
      payload.put("discountMax", p.discountMax());
      payload.put("validUntil", validUntil.toString());
      payload.put("issuedAt", at.toString());
      events.publish(
          codec.create(
              events.sourceSystem(),
              "CouponIssued",
              1,
              "Coupon",
              id,
              1,
              new com.pmart.common.messaging.EventEnvelope.Actor(
                  com.pmart.common.messaging.EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
              com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
              payload),
          COUPON_TOPIC);
    }
    return id;
  }

  private static PmartException unavailable(String why) {
    return "LIMIT".equals(why)
        ? new PmartException(
            "COUPON_LIMIT_REACHED",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "이미 받을 수 있는 만큼 받은 쿠폰입니다.",
            false,
            List.of())
        : new PmartException(
            "COUPON_SOLD_OUT", HttpStatus.UNPROCESSABLE_CONTENT, "쿠폰이 모두 나갔습니다.", false, List.of());
  }

  private PromotionRow lockedRunning(String promotionId) {
    PromotionRow p =
        store
            .lock(promotionId)
            .filter(x -> "COUPON".equals(x.kind()))
            .orElseThrow(() -> PmartException.notFound("PROMOTION_NOT_FOUND", "쿠폰 행사가 없습니다."));
    if (!"RUNNING"
        .equals(PromotionRules.phase(p.state(), p.startsAt(), p.endsAt(), p.endedAt(), now()))) {
      throw new PmartException(
          "NOT_ACTIVE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지금 진행 중인 쿠폰 행사가 아닙니다.",
          false,
          List.of());
    }
    return p;
  }

  // ------------------------------------------------------------------ 계산·점유(서비스)

  public Quote quote(String couponId, String accountId, long itemAmount, @Nullable Instant at) {
    return quote(
        store.findCoupon(couponId).orElse(null), couponId, accountId, itemAmount, null, at);
  }

  private Quote quote(
      @Nullable CouponRow c,
      String couponId,
      String accountId,
      long itemAmount,
      @Nullable String forOrder,
      @Nullable Instant at) {
    if (c == null) {
      return new Quote(couponId, false, "NOT_FOUND", 0, null, null);
    }
    PromotionRow p = store.find(c.promotionId()).orElseThrow();
    if (!c.accountId().equals(accountId)) {
      return new Quote(couponId, false, "NOT_OWNER", 0, p.promotionId(), p.name());
    }
    Instant when = at == null ? now() : at;
    if (when.isAfter(c.validUntil())) {
      return new Quote(couponId, false, "EXPIRED", 0, p.promotionId(), p.name());
    }
    if ("USED".equals(c.state())) {
      return new Quote(couponId, false, "USED", 0, p.promotionId(), p.name());
    }
    if ("HELD".equals(c.state()) && (forOrder == null || !forOrder.equals(c.orderId()))) {
      return new Quote(couponId, false, "HELD", 0, p.promotionId(), p.name());
    }
    long min = p.minOrderAmount() == null ? 0 : p.minOrderAmount();
    if (itemAmount < min) {
      return new Quote(couponId, false, "MIN_ORDER_NOT_MET", 0, p.promotionId(), p.name());
    }
    long d = PromotionRules.couponDiscount(PromotionInputs.discount(p), itemAmount);
    return new Quote(couponId, true, null, d, p.promotionId(), p.name());
  }

  public HoldRow hold(
      String orderId,
      String couponId,
      String accountId,
      long itemAmount,
      @Nullable Instant at,
      String requestId,
      String sourceSystem) {
    String holdId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "holdCoupon", RequestIdempotency.ANONYMOUS),
                Map.of(
                    "orderId",
                    orderId,
                    "couponId",
                    couponId,
                    "accountId",
                    accountId,
                    "itemAmount",
                    itemAmount),
                String.class,
                () -> {
                  CouponRow c = store.lockCoupon(couponId).orElse(null);
                  if (c != null) {
                    var existing = store.liveHoldFor(couponId, orderId);
                    if (existing.isPresent()) {
                      return existing.get().holdId();
                    }
                  }
                  Quote q = quote(c, couponId, accountId, itemAmount, null, at);
                  if (!q.applicable()) {
                    throw new PmartException(
                        "COUPON_NOT_APPLICABLE",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "이 주문에 쓸 수 없는 쿠폰입니다(" + q.reason() + ").",
                        false,
                        List.of(
                            new PmartException.Detail(
                                "couponId", java.util.Objects.requireNonNull(q.reason()))));
                  }
                  Instant now = now();
                  String id = "CPH-" + UUID.randomUUID();
                  store.couponState(couponId, "HELD", orderId, null, now);
                  store.insertHold(
                      new HoldRow(
                          id,
                          couponId,
                          orderId,
                          accountId,
                          "HELD",
                          itemAmount,
                          q.discountAmount(),
                          null),
                      now);
                  return id;
                })
            .value();
    return store.findHold(holdId).orElseThrow();
  }

  public HoldRow getHold(String holdId) {
    return store
        .findHold(holdId)
        .orElseThrow(() -> PmartException.notFound("HOLD_NOT_FOUND", "쿠폰 점유가 없습니다."));
  }

  public HoldRow confirm(String holdId) {
    return transition(
        holdId,
        h -> {
          switch (h.state()) {
            case "CONFIRMED" -> {
              return;
            }
            case "HELD" -> {
              Instant now = now();
              store.holdState(holdId, "CONFIRMED", null, now);
              store.couponState(h.couponId(), "USED", h.orderId(), now, now);
              store.addUsed(store.findCoupon(h.couponId()).orElseThrow().promotionId(), 1);
            }
            default -> throw PmartException.invalidState("확정할 수 없는 점유입니다(지금 " + h.state() + ").");
          }
        });
  }

  public HoldRow release(String holdId) {
    return transition(
        holdId,
        h -> {
          switch (h.state()) {
            case "RELEASED" -> {
              return;
            }
            case "HELD" -> {
              Instant now = now();
              store.holdState(holdId, "RELEASED", null, now);
              store.couponState(h.couponId(), "AVAILABLE", null, null, now);
            }
            default ->
                throw PmartException.invalidState(
                    "해제할 수 없는 점유입니다(지금 " + h.state() + " — 확정된 사용은 복원으로).");
          }
        });
  }

  /** 전체 취소 확정 뒤 복원 — 원래 사용 기한이 남았을 때만 다시 쓸 수 있다. */
  public HoldRow restore(String holdId) {
    return transition(
        holdId,
        h -> {
          switch (h.state()) {
            case "RESTORED" -> {
              return;
            }
            case "CONFIRMED" -> {
              Instant now = now();
              CouponRow c = store.findCoupon(h.couponId()).orElseThrow();
              boolean usable = !now.isAfter(c.validUntil());
              store.holdState(holdId, "RESTORED", usable, now);
              store.couponState(h.couponId(), "AVAILABLE", null, null, now);
              store.addUsed(c.promotionId(), -1);
            }
            default -> throw PmartException.invalidState("복원할 수 없는 점유입니다(지금 " + h.state() + ").");
          }
        });
  }

  private HoldRow transition(String holdId, java.util.function.Consumer<HoldRow> step) {
    tx.executeWithoutResult(
        st -> {
          HoldRow h =
              store
                  .lockHold(holdId)
                  .orElseThrow(() -> PmartException.notFound("HOLD_NOT_FOUND", "쿠폰 점유가 없습니다."));
          store.lockCoupon(h.couponId());
          step.accept(h);
        });
    return store.findHold(holdId).orElseThrow();
  }

  // ------------------------------------------------------------------ 보기

  CouponView view(CouponRow c) {
    return new CouponView(c, store.find(c.promotionId()).orElseThrow(), status(c));
  }

  private String status(CouponRow c) {
    if ("HELD".equals(c.state()) || "USED".equals(c.state())) {
      return "USED";
    }
    return now().isAfter(c.validUntil()) ? "EXPIRED" : "AVAILABLE";
  }

  private Instant now() {
    return clock.instant();
  }
}
