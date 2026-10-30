package com.pmart.product.application;

import com.pmart.common.web.PmartException;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 상품·SKU·바코드 조회. 상품 상세는 승인 버전 스냅샷(product_version)을 그대로 돌려주고, SKU 응답에는 상품의 전사 판매 상태를 덧붙인다(계약
 * SkuDetail/Product 모양의 Map).
 */
@Service
public class ProductQueries {

  private final CatalogStore catalog;
  private final ObjectMapper mapper;

  public ProductQueries(CatalogStore catalog, ObjectMapper mapper) {
    this.catalog = catalog;
    this.mapper = mapper;
  }

  /** 상품 상세(현재 또는 지정 승인 버전). */
  @SuppressWarnings("unchecked")
  public Map<String, Object> product(String productId, @Nullable Integer version) {
    ProductRow p =
        catalog
            .findProduct(productId)
            .orElseThrow(
                () -> PmartException.notFound("PRODUCT_NOT_FOUND", "상품이 없습니다: " + productId));
    int v = version == null ? p.currentVersion() : version;
    String json =
        catalog
            .versionSnapshot(productId, v)
            .orElseThrow(
                () -> PmartException.notFound("PRODUCT_VERSION_NOT_FOUND", "상품 버전이 없습니다: " + v));
    Map<String, Object> snap = mapper.readValue(json, LinkedHashMap.class);
    if (version == null) {
      // 판매 상태는 승인 버전과 별개로 바뀐다(판매 상태 변경 명령) — 현재 조회는 현재 상태
      snap.put("saleState", p.saleState());
    }
    List<Map<String, Object>> skus = new ArrayList<>();
    for (Object o : (List<Object>) snap.getOrDefault("skus", List.of())) {
      skus.add(skuDetail((Map<String, Object>) o, snap, v));
    }
    snap.put("skus", skus);
    return snap;
  }

  public List<ProductRow> list(
      @Nullable String categoryId,
      @Nullable String saleState,
      @Nullable String afterId,
      int limit) {
    return catalog.listProducts(categoryId, saleState, afterId, limit);
  }

  public Map<String, Object> sku(String sku) {
    return detail(
        catalog
            .findSku(sku)
            .orElseThrow(() -> PmartException.notFound("SKU_NOT_FOUND", "SKU 가 없습니다: " + sku)));
  }

  public List<Map<String, Object>> skus(List<String> skus) {
    return catalog.findSkus(skus).stream().map(this::detail).toList();
  }

  public Map<String, Object> barcode(String barcode) {
    String sku =
        catalog
            .activeBarcodeOwner(barcode.strip())
            .orElseThrow(
                () -> PmartException.notFound("BARCODE_NOT_MAPPED", "연결된 SKU 가 없는 바코드입니다."));
    SkuRow s = catalog.findSku(sku).orElseThrow();
    ProductRow p = catalog.findProduct(s.productId()).orElseThrow();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("barcode", barcode.strip());
    m.put("sku", s.sku());
    m.put("productId", s.productId());
    m.put("unitType", s.unitType());
    m.put("unitsPerSku", s.unitsPerSku());
    m.put("version", p.currentVersion());
    return m;
  }

  private Map<String, Object> detail(SkuRow s) {
    ProductRow p = catalog.findProduct(s.productId()).orElseThrow();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", s.sku());
    m.put("unitType", s.unitType());
    m.put("unitsPerSku", s.unitsPerSku());
    m.put("weightG", s.weightG());
    m.put("storageType", s.storageType());
    m.put("alcohol", s.alcohol());
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
    Map<String, Object> product = new LinkedHashMap<>();
    product.put("productId", p.productId());
    product.put("baseProductId", p.baseProductId());
    product.put("saleState", "ACTIVE".equals(s.state()) ? p.saleState() : "SALE_STOPPED");
    return skuDetail(m, product, p.currentVersion());
  }

  /** 스냅샷의 sku 행 → 계약 SkuDetail(보관 온도는 logistics.storageType). */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> skuDetail(
      Map<String, Object> s, Map<String, Object> product, int version) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", s.get("sku"));
    m.put("productId", product.get("productId"));
    m.put("baseProductId", product.get("baseProductId"));
    m.put("unitType", s.get("unitType"));
    m.put("unitsPerSku", s.get("unitsPerSku"));
    m.put("weightG", s.get("weightG"));
    m.put(
        "saleState", "INACTIVE".equals(s.get("state")) ? "SALE_STOPPED" : product.get("saleState"));
    m.put("alcohol", s.get("alcohol"));
    m.put("barcodes", s.getOrDefault("barcodes", List.of()));
    Map<String, Object> lg =
        new LinkedHashMap<>((Map<String, Object>) s.getOrDefault("logistics", Map.of()));
    lg.put("storageType", s.get("storageType"));
    m.put("logistics", lg);
    m.put("version", version);
    if (product.get("publishedAt") != null) {
      m.put("publishedAt", product.get("publishedAt"));
    }
    return m;
  }

  private static void putIfNotNull(Map<String, Object> m, String k, @Nullable Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }
}
