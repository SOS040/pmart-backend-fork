package com.pmart.promotion.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.Candidate;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 수량 행사 1+1·2+1(ADR-0090, 0.5.0) — SKU 마다 지금 걸린 행사 1개(무료 비율이 큰 것 → 먼저 끝나는 것 → 행사 ID). pricing 견적·화면
 * 표시·홈 "+1 하나더" 목록이 부른다. 대상 판정은 기간 할인({@link PriceDiscounts})과 같다.
 */
@Service
public class QuantityOffers {

  public static final int MAX_SKUS = 500;

  public record Result(String sku, @Nullable String productId, PromotionRow promotion) {

    public PromotionRules.Offer offer() {
      return offerOf(promotion);
    }
  }

  private final PromotionStore store;
  private final Clock clock;

  public QuantityOffers(PromotionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Instant asOf(@Nullable Instant at) {
    return at == null ? clock.instant() : at;
  }

  /** 쉼표 구분 SKU(1~500, 중복 한 번) → 걸린 행사가 있는 SKU 만, 요청 순서. */
  public List<Result> lookup(@Nullable String skusCsv, Instant at) {
    Set<String> skus = new LinkedHashSet<>();
    if (skusCsv != null) {
      for (String s : skusCsv.split(",")) {
        if (!s.isBlank()) {
          skus.add(s.strip());
        }
      }
    }
    if (skus.isEmpty() || skus.size() > MAX_SKUS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "skus 는 1~" + MAX_SKUS + "개입니다.");
    }
    List<Candidate> candidates = store.offerCandidates(skus, at);
    if (candidates.isEmpty()) {
      return List.of();
    }
    Map<String, List<String>> cats = store.categoriesOf(skus);
    Map<String, List<String>> themes = store.themesOf(skus);
    Map<String, String> products = store.productsOf(skus);
    List<Result> out = new ArrayList<>();
    for (String sku : skus) {
      List<String> mine = cats.getOrDefault(sku, List.of());
      List<String> myThemes = themes.getOrDefault(sku, List.of());
      PromotionRow best = null;
      for (Candidate c : candidates) {
        boolean hit =
            ("SKU".equals(c.targetType()) && c.targetId().equals(sku))
                || ("CATEGORY".equals(c.targetType()) && mine.contains(c.targetId()))
                || ("THEME".equals(c.targetType()) && myThemes.contains(c.targetId()));
        if (hit && (best == null || better(c.promotion(), best))) {
          best = c.promotion();
        }
      }
      if (best != null) {
        out.add(new Result(sku, products.get(sku), best));
      }
    }
    return out;
  }

  /** 진행 중 수량 행사 SKU(홈 "+1 하나더") — SKU 마다 1개, 끝이 빠른 순 → SKU, limit 개. */
  public List<Result> running(int limit) {
    Map<String, Result> bySku = new LinkedHashMap<>();
    for (var t : store.runningOfferTargets(clock.instant())) {
      Result cur = bySku.get(t.sku());
      if (cur == null || better(t.promotion(), cur.promotion())) {
        bySku.put(t.sku(), new Result(t.sku(), t.productId(), t.promotion()));
      }
    }
    return bySku.values().stream()
        .sorted(
            Comparator.comparing((Result r) -> r.promotion().endsAt()).thenComparing(Result::sku))
        .limit(limit)
        .toList();
  }

  static PromotionRules.Offer offerOf(PromotionRow p) {
    return new PromotionRules.Offer(
        p.offerBuy() == null ? 1 : p.offerBuy(), p.offerFree() == null ? 0 : p.offerFree());
  }

  private static boolean better(PromotionRow p, PromotionRow best) {
    int byRatio = PromotionRules.compareOffers(offerOf(p), offerOf(best));
    if (byRatio != 0) {
      return byRatio > 0;
    }
    int byEnd = p.endsAt().compareTo(best.endsAt());
    if (byEnd != 0) {
      return byEnd < 0;
    }
    return p.promotionId().compareTo(best.promotionId()) < 0;
  }
}
