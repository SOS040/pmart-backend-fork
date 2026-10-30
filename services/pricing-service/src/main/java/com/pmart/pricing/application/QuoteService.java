package com.pmart.pricing.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.pricing.domain.PriceRules;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 견적 계산(1차는 기본가만: BASE 단계). 결과는 quoteId 로 보존해 응답 유실 시 재조회한다. 가격 미설정 SKU 가 하나라도 있으면 전체
 * PRICE_NOT_SET. 배달팁·봉투값·최소주문은 order 책임이라 계산하지 않는다.
 */
@Service
public class QuoteService {

  public record QuoteItem(String lineRef, String sku, int quantity) {}

  public record QuoteLine(
      String lineRef,
      String sku,
      int quantity,
      long unitPrice,
      int priceVersion,
      long lineAmount,
      long discountShare,
      long netAmount) {}

  public record Quote(
      String quoteId,
      String requestId,
      int priceVersion,
      List<QuoteLine> lines,
      long itemAmount,
      long discountTotal,
      List<String> appliedStages,
      Instant priceAt,
      Instant calculatedAt) {}

  private final PriceStore store;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public QuoteService(
      PriceStore store, RequestIdempotency idempotency, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Quote create(
      String requestId,
      String sourceSystem,
      @Nullable Instant at,
      List<QuoteItem> items,
      Object body) {
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createQuote", actor),
            body,
            Quote.class,
            () -> calculate(requestId, at, items))
        .value();
  }

  public Quote get(String quoteId) {
    return store
        .quote(quoteId)
        .map(json -> mapper.readValue(json, Quote.class))
        .orElseThrow(() -> PmartException.notFound("QUOTE_NOT_FOUND", "견적이 없습니다."));
  }

  private Quote calculate(String requestId, @Nullable Instant at, List<QuoteItem> items) {
    Set<String> refs = new HashSet<>();
    for (QuoteItem i : items) {
      if (!refs.add(i.lineRef())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "lineRef " + i.lineRef() + " 가 중복되었습니다.",
            List.of(new PmartException.Detail("items[].lineRef", "DUPLICATE")));
      }
    }
    Instant now = clock.instant();
    Instant priceAt = at == null ? now : at;
    Map<String, List<PriceVersion>> versions =
        store.versions(items.stream().map(QuoteItem::sku).distinct().toList());

    List<PmartException.Detail> missing = new ArrayList<>();
    List<QuoteLine> lines = new ArrayList<>();
    long itemAmount = 0;
    int maxVersion = 0;
    for (QuoteItem i : items) {
      Optional<PriceVersion> v =
          PriceRules.effectiveAt(versions.getOrDefault(i.sku(), List.of()), priceAt);
      if (v.isEmpty()) {
        missing.add(new PmartException.Detail(i.sku(), "PRICE_NOT_SET"));
        continue;
      }
      long amount = PriceRules.lineAmount(v.get().basePrice(), i.quantity());
      lines.add(
          new QuoteLine(
              i.lineRef(),
              i.sku(),
              i.quantity(),
              v.get().basePrice(),
              v.get().version(),
              amount,
              0,
              amount));
      itemAmount = Math.addExact(itemAmount, amount);
      maxVersion = Math.max(maxVersion, v.get().version());
    }
    if (!missing.isEmpty()) {
      throw new PmartException(
          "PRICE_NOT_SET",
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "가격이 없는 SKU 가 있습니다. 해당 품목을 빼야 합니다.",
          false,
          missing);
    }
    Quote quote =
        new Quote(
            "QT-" + UUID.randomUUID(),
            requestId,
            maxVersion,
            lines,
            itemAmount,
            0,
            List.of("BASE"),
            priceAt,
            now);
    store.insertQuote(quote.quoteId(), requestId, mapper.writeValueAsString(quote), now);
    return quote;
  }
}
