package com.pmart.pricing.api;

import com.pmart.pricing.api.generated.InternalApi;
import com.pmart.pricing.api.generated.model.ListPrices200Response;
import com.pmart.pricing.api.generated.model.Quote;
import com.pmart.pricing.api.generated.model.QuoteRequest;
import com.pmart.pricing.application.PriceQueries;
import com.pmart.pricing.application.QuoteService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 서비스 간 견적·가격 조회(내부 호출). 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class InternalController implements InternalApi {

  private final QuoteService quotes;
  private final PriceQueries queries;

  public InternalController(QuoteService quotes, PriceQueries queries) {
    this.quotes = quotes;
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Quote> createQuote(
      String idempotencyKey,
      String xSourceSystem,
      QuoteRequest body,
      @Nullable String traceparent) {
    List<QuoteService.QuoteItem> items =
        body.getItems().stream()
            .map(i -> new QuoteService.QuoteItem(i.getLineRef(), i.getSku(), i.getQuantity()))
            .toList();
    return ResponseEntity.ok(
        PricingMapping.quote(
            quotes.create(
                idempotencyKey,
                xSourceSystem,
                PricingMapping.instant(body.getAt()),
                body.getAccountId(),
                body.getCouponIds() == null || body.getCouponIds().isEmpty()
                    ? null
                    : body.getCouponIds().getFirst(),
                items,
                body)));
  }

  @Override
  public ResponseEntity<Quote> getQuote(String quoteId) {
    return ResponseEntity.ok(PricingMapping.quote(quotes.get(quoteId)));
  }

  @Override
  public ResponseEntity<ListPrices200Response> listPricesInternal(List<String> skus) {
    return ResponseEntity.ok(PricingMapping.priceList(queries.prices(skus, null)));
  }
}
