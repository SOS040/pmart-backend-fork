package com.pmart.product.api;

import com.pmart.product.api.generated.InternalApi;
import com.pmart.product.api.generated.model.ListSkusInternal200Response;
import com.pmart.product.api.generated.model.Product;
import com.pmart.product.api.generated.model.SkuDetail;
import com.pmart.product.application.ProductQueries;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi(서비스 토큰). 구매 상한 검증은 아직 구현하지 않았다(인터페이스 default 501, 정책값 등록 후 슬라이스 2). 업무 규칙은
 * application 에 둔다.
 */
@RestController
public class InternalController implements InternalApi {

  private final ProductQueries queries;
  private final RegistrationMapping mapping;

  public InternalController(ProductQueries queries, RegistrationMapping mapping) {
    this.queries = queries;
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
  public ResponseEntity<Product> getProductVersionInternal(String productId, Integer version) {
    return ResponseEntity.ok(mapping.convert(queries.product(productId, version), Product.class));
  }
}
