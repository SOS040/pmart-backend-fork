package com.pmart.product.api;

import com.pmart.product.api.generated.ProductsApi;
import com.pmart.product.api.generated.model.ListProducts200Response;
import com.pmart.product.api.generated.model.Product;
import com.pmart.product.api.generated.model.SaleState;
import com.pmart.product.api.generated.model.SkuDetail;
import com.pmart.product.application.Operator;
import com.pmart.product.application.ProductQueries;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ProductsApi — 상품·SKU 조회(승인 버전 기준). */
@RestController
public class ProductsController implements ProductsApi {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;

  private final ProductQueries queries;
  private final RegistrationMapping mapping;

  public ProductsController(ProductQueries queries, RegistrationMapping mapping) {
    this.queries = queries;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<Product> getProduct(String productId, @Nullable Integer version) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    return ResponseEntity.ok(mapping.convert(queries.product(productId, version), Product.class));
  }

  @Override
  public ResponseEntity<SkuDetail> getSku(String sku) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    return ResponseEntity.ok(mapping.convert(queries.sku(sku), SkuDetail.class));
  }

  @Override
  public ResponseEntity<ListProducts200Response> listProducts(
      @Nullable String categoryId,
      @Nullable String q,
      @Nullable SaleState saleState,
      @Nullable Integer size,
      @Nullable String cursor) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    int limit = Math.min(size == null ? DEFAULT_SIZE : Math.max(size, 1), MAX_SIZE);
    List<ProductRow> rows =
        queries.list(
            categoryId, q, saleState == null ? null : saleState.getValue(), cursor, limit + 1);
    boolean more = rows.size() > limit;
    List<ProductRow> page = more ? rows.subList(0, limit) : rows;
    List<Map<String, Object>> items =
        page.stream()
            .map(
                p -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("productId", p.productId());
                  m.put("baseProductId", p.baseProductId());
                  m.put("internalName", p.internalName());
                  m.put("primaryCategoryId", p.primaryCategoryId());
                  m.put("saleState", p.saleState());
                  m.put("version", p.currentVersion());
                  return m;
                })
            .toList();
    Map<String, Object> pageInfo = new HashMap<>();
    pageInfo.put("nextCursor", more ? page.get(page.size() - 1).productId() : null);
    pageInfo.put("size", page.size());
    return ResponseEntity.ok(
        mapping.convert(Map.of("items", items, "page", pageInfo), ListProducts200Response.class));
  }
}
