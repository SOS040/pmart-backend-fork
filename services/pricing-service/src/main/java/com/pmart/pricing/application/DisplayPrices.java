package com.pmart.pricing.application;

import com.pmart.pricing.application.PriceQueries.CurrentPrice;
import com.pmart.pricing.application.PriceQueries.PriceList;
import com.pmart.pricing.infrastructure.PromotionClient;
import com.pmart.pricing.infrastructure.PromotionClient.DisplayDiscount;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 표시용 가격 + 지금 기간 할인(ADR-0082 4단계). 할인은 promotion 이 견적과 같은 규칙(가장 큰 1개)으로 계산한다. promotion 을 못 읽으면 정가만
 * 보이고 {@code discountAvailable=false} — 표시용이라 거절하지 않는다(결제 금액은 견적이 정하고, 그때는 503).
 */
@Service
public class DisplayPrices {

  private static final Logger log = LoggerFactory.getLogger(DisplayPrices.class);

  /** offers = 수량 행사(0.4.1 ADR-0090) — 걸린 SKU 는 discounts 에 없다(A5). */
  public record Shown(
      PriceList list,
      Map<String, DisplayDiscount> discounts,
      boolean available,
      Map<String, PromotionClient.Offer> offers) {

    public Shown(PriceList list, Map<String, DisplayDiscount> discounts, boolean available) {
      this(list, discounts, available, Map.of());
    }
  }

  private final PromotionClient promotion;
  private final Clock clock;

  public DisplayPrices(PromotionClient promotion, Clock clock) {
    this.promotion = promotion;
    this.clock = clock;
  }

  /** 한 SKU. at 이 없으면 지금. */
  public Shown withDiscounts(CurrentPrice p, @Nullable Instant at) {
    return withDiscounts(
        new PriceList(List.of(p), List.of(), at == null ? clock.instant() : at),
        at == null ? clock.instant() : at);
  }

  public Shown withDiscounts(PriceList list, Instant at) {
    Map<String, Long> prices = new LinkedHashMap<>();
    for (CurrentPrice p : list.items()) {
      prices.put(p.current().sku(), p.current().basePrice());
    }
    try {
      Map<String, PromotionClient.Offer> offers = promotion.offers(prices.keySet(), at);
      offers.keySet().forEach(prices::remove);
      return new Shown(list, promotion.displayDiscounts(prices, at), true, offers);
    } catch (RuntimeException ex) {
      log.warn("표시 할인을 못 읽음 — 정가만 보임: {}", ex.toString());
      return new Shown(list, Map.of(), false);
    }
  }
}
