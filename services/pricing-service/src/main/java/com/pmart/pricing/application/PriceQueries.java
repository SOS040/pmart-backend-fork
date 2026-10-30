package com.pmart.pricing.application;

import com.pmart.common.web.PmartException;
import com.pmart.pricing.domain.PriceRules;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** 가격 조회(표시용). 가격이 없으면 0원이 아니라 PRICE_NOT_SET 이다. */
@Service
public class PriceQueries {

  /** 시각 t 의 가격과 그 뒤 예약된 다음 변경. */
  public record CurrentPrice(PriceVersion current, @Nullable PriceVersion next) {}

  public record PriceList(List<CurrentPrice> items, List<String> notSet, Instant asOf) {}

  public record History(List<PriceVersion> items, @Nullable String nextCursor) {}

  private final PriceStore store;
  private final Clock clock;

  public PriceQueries(PriceStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Instant now() {
    return clock.instant();
  }

  public CurrentPrice price(String sku, @Nullable Instant at) {
    Instant t = at == null ? clock.instant() : at;
    return resolve(store.versions(sku, false), t).orElseThrow(() -> priceNotSet(sku));
  }

  public PriceList prices(List<String> skus, @Nullable Instant at) {
    Instant t = at == null ? clock.instant() : at;
    List<String> distinct = List.copyOf(new LinkedHashSet<>(skus));
    Map<String, List<PriceVersion>> all = store.versions(distinct);
    List<CurrentPrice> items = new ArrayList<>();
    List<String> notSet = new ArrayList<>();
    for (String sku : distinct) {
      resolve(all.getOrDefault(sku, List.of()), t)
          .ifPresentOrElse(items::add, () -> notSet.add(sku));
    }
    return new PriceList(items, notSet, t);
  }

  public History history(String sku, int size, @Nullable String cursor) {
    Integer before;
    try {
      before = cursor == null || cursor.isBlank() ? null : Integer.valueOf(cursor);
    } catch (NumberFormatException e) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "cursor 형식이 잘못되었습니다.");
    }
    List<PriceVersion> rows = store.history(sku, before, size + 1);
    boolean more = rows.size() > size;
    List<PriceVersion> page = more ? rows.subList(0, size) : rows;
    return new History(page, more ? String.valueOf(page.getLast().version()) : null);
  }

  static Optional<CurrentPrice> resolve(List<PriceVersion> versions, Instant at) {
    return PriceRules.effectiveAt(versions, at)
        .map(v -> new CurrentPrice(v, PriceRules.nextAfter(versions, at).orElse(null)));
  }

  static PmartException priceNotSet(String sku) {
    return PmartException.notFound("PRICE_NOT_SET", "SKU " + sku + " 의 유효 가격이 없습니다(판매 불가).");
  }
}
