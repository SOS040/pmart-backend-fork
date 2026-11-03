package com.pmart.product.api;

import com.pmart.product.api.generated.InternalApi;
import com.pmart.product.api.generated.model.ListSkusInternal200Response;
import com.pmart.product.api.generated.model.LookupSourceLinks200Response;
import com.pmart.product.api.generated.model.LookupSourceLinksRequest;
import com.pmart.product.api.generated.model.Product;
import com.pmart.product.api.generated.model.PurchaseLimitCheckRequest;
import com.pmart.product.api.generated.model.PurchaseLimitCheckResult;
import com.pmart.product.api.generated.model.SkuDetail;
import com.pmart.product.application.ProductQueries;
import com.pmart.product.application.PurchaseLimitService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi(내부 호출). 업무 규칙은 application 에 둔다. */
@RestController
public class InternalController implements InternalApi {

  private final ProductQueries queries;
  private final RegistrationMapping mapping;
  private final PurchaseLimitService limits;

  public InternalController(
      ProductQueries queries, RegistrationMapping mapping, PurchaseLimitService limits) {
    this.queries = queries;
    this.limits = limits;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<SkuDetail> getSkuInternal(String sku) {
    return ResponseEntity.ok(mapping.convert(queries.sku(sku), SkuDetail.class));
  }

  @Override
  public ResponseEntity<ListSkusInternal200Response> listSkusInternal(List<String> skus) {
    List<Map<String, Object>> items = queries.skus(skus.stream().distinct().toList());
    Set<String> found = new HashSet<>();
    items.forEach(i -> found.add((String) i.get("sku")));
    List<String> missing = skus.stream().filter(s -> !found.contains(s)).distinct().toList();
    Map<String, Object> body =
        Map.of(
            "items",
            items,
            "missing",
            missing,
            "asOf",
            Map.of("asOf", Instant.now().toString(), "freshness", "CURRENT"));
    return ResponseEntity.ok(mapping.convert(body, ListSkusInternal200Response.class));
  }

  @Override
  public ResponseEntity<LookupSourceLinks200Response> lookupSourceLinks(
      String xSourceSystem, LookupSourceLinksRequest body) {
    return ResponseEntity.ok(
        mapping.convert(
            queries.sourceLinks(body.getSourceSystem(), body.getSourceKeys()),
            LookupSourceLinks200Response.class));
  }

  @Override
  public ResponseEntity<Product> getProductVersionInternal(String productId, Integer version) {
    return ResponseEntity.ok(mapping.convert(queries.product(productId, version), Product.class));
  }

  @Override
  public ResponseEntity<PurchaseLimitCheckResult> checkPurchaseLimit(
      String xSourceSystem, PurchaseLimitCheckRequest body, @Nullable String traceparent) {
    PurchaseLimitService.Result r =
        limits.check(
            body.getItems().stream()
                .map(i -> new PurchaseLimitService.Item(i.getSku(), i.getQuantity()))
                .toList());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("passed", r.violations().isEmpty());
    out.put("limitPerBaseProduct", Map.of("state", "VALUE", "value", r.limitUnits()));
    out.put("violations", r.violations());
    out.put("asOf", Map.of("asOf", r.asOf().toString(), "freshness", "CURRENT"));
    return ResponseEntity.ok(mapping.convert(out, PurchaseLimitCheckResult.class));
  }
}
