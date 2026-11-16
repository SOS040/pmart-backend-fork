package com.pmart.pricing.api;

import com.pmart.pricing.api.generated.PricesApi;
import com.pmart.pricing.api.generated.model.ListPriceVersions200Response;
import com.pmart.pricing.api.generated.model.ListPriceVersions200ResponsePage;
import com.pmart.pricing.api.generated.model.ListPrices200Response;
import com.pmart.pricing.api.generated.model.Price;
import com.pmart.pricing.application.PriceImportService;
import com.pmart.pricing.application.PriceQueries;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 PricesApi — 표시용 가격 조회. 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class PricesController implements PricesApi {

  private final PriceQueries queries;

  public PricesController(PriceQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Price> getPrice(String sku, @Nullable OffsetDateTime at) {
    return ResponseEntity.ok(PricingMapping.price(queries.price(sku, PricingMapping.instant(at))));
  }

  @Override
  public ResponseEntity<ListPrices200Response> listPrices(List<String> skus) {
    return ResponseEntity.ok(PricingMapping.priceList(queries.prices(skus, null)));
  }

  @Override
  public ResponseEntity<ListPriceVersions200Response> listPriceVersions(
      String sku, Integer size, @Nullable String cursor) {
    PriceImportService.requireOperator(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    PriceQueries.History h = queries.history(sku, size, cursor);
    return ResponseEntity.ok(
        new ListPriceVersions200Response()
            .items(h.items().stream().map(PricingMapping::version).toList())
            .page(new ListPriceVersions200ResponsePage().nextCursor(h.nextCursor()).size(size)));
  }
}
