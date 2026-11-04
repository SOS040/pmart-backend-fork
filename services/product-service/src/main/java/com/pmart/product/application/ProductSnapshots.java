package com.pmart.product.application;

import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 상품 승인 버전 스냅샷. product_version 에 불변으로 저장하고 ProductVersionPublished 본문·조회 응답의 원천으로 쓴다(같은 모양을 한 곳에서
 * 만든다).
 */
@Component
public class ProductSnapshots {

  private final CatalogStore catalog;

  public ProductSnapshots(CatalogStore catalog) {
    this.catalog = catalog;
  }

  public Map<String, Object> build(String productId, int version, String approvalId, Instant at) {
    ProductRow p = catalog.findProduct(productId).orElseThrow();
    List<Map<String, Object>> skus = new ArrayList<>();
    for (SkuRow s : catalog.skusOf(productId)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", s.sku());
      m.put("unitType", s.unitType());
      m.put("unitsPerSku", s.unitsPerSku());
      m.put("weightG", s.weightG());
      if (s.volumeMl() != null) {
        m.put("volumeMl", s.volumeMl());
      }
      m.put("storageType", s.storageType());
      m.put("alcohol", s.alcohol());
      m.put("state", s.state());
      m.put("barcodes", catalog.activeBarcodes(s.sku()));
      catalog
          .latestLogistics(s.sku())
          .ifPresent(
              l -> {
                Map<String, Object> lg = new LinkedHashMap<>();
                putIfNotNull(lg, "unitsPerBox", l.unitsPerBox());
                putIfNotNull(lg, "boxesPerPallet", l.boxesPerPallet());
                putIfNotNull(lg, "ti", l.ti());
                putIfNotNull(lg, "hi", l.hi());
                m.put("logistics", lg);
              });
      skus.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("productId", p.productId());
    out.put("baseProductId", p.baseProductId());
    out.put("internalName", p.internalName());
    out.put("primaryCategoryId", p.primaryCategoryId());
    out.put("categoryIds", catalog.categoriesOf(productId));
    out.put("saleState", p.saleState());
    // 승인 속성(필터 옵션) — 검색이 filters(그룹.옵션)로 거른다(2026-11-04)
    List<Map<String, Object>> filterOptions = new ArrayList<>();
    for (String[] go : catalog.filterOptionsOf(productId)) {
      filterOptions.add(Map.of("filterGroupId", go[0], "option", go[1]));
    }
    out.put("filterOptions", filterOptions);
    out.put("skus", skus);
    out.put("version", version);
    out.put("approvalId", approvalId);
    out.put("publishedAt", at.toString());
    return out;
  }

  private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }
}
