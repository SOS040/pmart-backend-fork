package com.pmart.product.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.domain.PurchaseLimit;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 구매 상한 검증(조회성, 상태 변경 없음). 상한은 설정값 {@code pmart.product.purchase-limit-units} 이고 비어 있으면
 * CONFIG_REQUIRED(무제한으로 대체하지 않는다).
 */
@Service
public class PurchaseLimitService {

  public record Item(String sku, int quantity) {}

  public record Result(int limitUnits, List<PurchaseLimit.Violation> violations, Instant asOf) {}

  private final CatalogStore catalog;
  private final Clock clock;
  private final String limit;

  public PurchaseLimitService(
      CatalogStore catalog,
      Clock clock,
      @Value("${pmart.product.purchase-limit-units:}") String limit) {
    this.catalog = catalog;
    this.clock = clock;
    this.limit = limit;
  }

  public Result check(List<Item> items) {
    if (limit.isBlank()) {
      throw PmartException.configRequired("pmart.product.purchase-limit-units");
    }
    int limitUnits = Integer.parseInt(limit.trim());
    Map<String, SkuRow> skus =
        catalog.findSkus(items.stream().map(Item::sku).distinct().toList()).stream()
            .collect(Collectors.toMap(SkuRow::sku, Function.identity()));
    List<PmartException.Detail> unknown = new ArrayList<>();
    List<PurchaseLimit.Line> lines = new ArrayList<>();
    for (Item i : items) {
      SkuRow s = skus.get(i.sku());
      if (s == null) {
        unknown.add(new PmartException.Detail(i.sku(), "SKU_NOT_FOUND"));
        continue;
      }
      lines.add(new PurchaseLimit.Line(s.sku(), s.baseProductId(), s.unitsPerSku(), i.quantity()));
    }
    if (!unknown.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "없는 SKU 가 있습니다.", unknown);
    }
    return new Result(limitUnits, PurchaseLimit.check(lines, limitUnits), clock.instant());
  }
}
