package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.promotion.infrastructure.DisplayDiscountStore;
import com.pmart.promotion.infrastructure.DisplayDiscountStore.Shown;
import com.pmart.promotion.infrastructure.DisplayDiscountStore.SkuPrice;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 할인가 표시(ADR-0082 4단계). 주기마다 "지금 진행 중인 기간 할인이 걸린 SKU + 지금 할인으로 보이는 SKU" 의 표시 할인을 다시 계산하고(견적과 같은
 * {@link PriceDiscounts} — 가장 큰 1개), 달라진 SKU 만 {@code
 * SkuDisplayDiscountChanged}(commerce.sku-display-discount.v1, 키 = SKU)로 알린다. 행사 시작·끝·조기 종료·가격
 * 변경·카테고리 상품 추가가 모두 주기 안에 반영된다. 받는 쪽(검색 색인·홈·…)은 seq 가 큰 것만, 그리고 자기 가격 사본의 priceVersion 이 같을 때만
 * 할인가를 보인다(가격이 막 바뀐 사이에는 정가).
 */
@Service
public class DisplayDiscountSync {

  public static final String TOPIC = "commerce.sku-display-discount.v1";
  static final int CHUNK = 500;
  private static final Logger log = LoggerFactory.getLogger(DisplayDiscountSync.class);

  private final DisplayDiscountStore store;
  private final PriceDiscounts discounts;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final QuantityOffers offers;

  public DisplayDiscountSync(
      DisplayDiscountStore store,
      PriceDiscounts discounts,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TransactionTemplate tx,
      Clock clock,
      QuantityOffers offers) {
    this.offers = offers;
    this.store = store;
    this.discounts = discounts;
    this.events = events;
    this.codec = codec;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.promotion.display-sync-interval:PT30S}",
      initialDelayString = "${pmart.promotion.display-sync-initial-delay:PT20S}")
  public void scheduled() {
    try {
      int n = sync();
      if (n > 0) {
        log.info("표시 할인 {}건 알림", n);
      }
    } catch (RuntimeException ex) {
      log.warn("표시 할인 계산 실패 — 다음 주기에 다시", ex);
    }
  }

  /** 한 번 돌기. 알린 SKU 수. */
  public int sync() {
    Instant now = clock.instant();
    TreeSet<String> skus = new TreeSet<>(store.targetedSkus(now));
    skus.addAll(store.shownDiscountedSkus());
    List<String> all = new ArrayList<>(skus);
    int changed = 0;
    for (int i = 0; i < all.size(); i += CHUNK) {
      List<String> chunk = all.subList(i, Math.min(all.size(), i + CHUNK));
      Integer n = tx.execute(s -> syncChunk(chunk, now));
      changed += n == null ? 0 : n;
    }
    return changed;
  }

  private int syncChunk(List<String> skus, Instant now) {
    if (!store.tryLock()) {
      return 0; // 다른 인스턴스가 도는 중
    }
    Map<String, SkuPrice> prices = store.prices(skus);
    List<PriceDiscounts.Item> items = new ArrayList<>();
    prices.values().forEach(p -> items.add(new PriceDiscounts.Item(p.sku(), p.price())));
    Map<String, PriceDiscounts.Result> best = new LinkedHashMap<>();
    discounts.lookup(items, now).forEach(r -> best.put(r.sku(), r));
    // ADR-0090 A5: 수량 행사가 걸린 SKU 는 결제 때 기간 할인이 빠진다 — 할인가로 보이지 않게(행사가 끝나면 다음 주기에 다시)
    offers.skusWithOffers(prices.keySet(), now).forEach(best::remove);
    Map<String, Shown> shown = store.shownForUpdate(skus);
    int changed = 0;
    for (String sku : skus) {
      SkuPrice price = prices.get(sku);
      if (price == null) {
        continue; // 가격 없는 SKU 는 할인가를 보일 수 없다
      }
      Shown before = shown.get(sku);
      PriceDiscounts.Result r = best.get(sku);
      Shown next = desired(sku, price, r, before == null ? 1 : before.seq() + 1);
      if (before == null ? next.unitDiscount() == 0 : same(before, next)) {
        continue;
      }
      store.save(next, now);
      publish(next, now);
      changed++;
    }
    return changed;
  }

  static Shown desired(String sku, SkuPrice price, PriceDiscounts.@Nullable Result r, int seq) {
    if (r == null) {
      return new Shown(
          sku, seq, price.priceVersion(), price.price(), 0, price.price(), 0, null, null, null);
    }
    return new Shown(
        sku,
        seq,
        price.priceVersion(),
        price.price(),
        r.unitDiscount(),
        price.price() - r.unitDiscount(),
        r.ratePercent(),
        r.promotion().promotionId(),
        r.promotion().name(),
        r.promotion().endsAt());
  }

  /** seq 를 빼고 같은가. */
  static boolean same(Shown a, Shown b) {
    return a.priceVersion() == b.priceVersion()
        && a.basePrice() == b.basePrice()
        && a.unitDiscount() == b.unitDiscount()
        && a.ratePercent() == b.ratePercent()
        && Objects.equals(a.promotionId(), b.promotionId())
        && Objects.equals(a.promotionName(), b.promotionName())
        && Objects.equals(a.endsAt(), b.endsAt());
  }

  private void publish(Shown s, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sku", s.sku());
    payload.put("seq", s.seq());
    payload.put("priceVersion", s.priceVersion());
    payload.put("basePrice", s.basePrice());
    payload.put("unitDiscount", s.unitDiscount());
    payload.put("salePrice", s.salePrice());
    payload.put("ratePercent", s.ratePercent());
    payload.put("promotionId", s.promotionId());
    payload.put("promotionName", s.promotionName());
    payload.put("endsAt", s.endsAt() == null ? null : s.endsAt().toString());
    payload.put("computedAt", now.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "SkuDisplayDiscountChanged",
            1,
            "SkuDisplayDiscount",
            s.sku(),
            s.seq(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }
}
