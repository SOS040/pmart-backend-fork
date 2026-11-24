package com.pmart.storefront.application;

import com.pmart.storefront.domain.ListAvailability;
import com.pmart.storefront.infrastructure.ContentAvailabilityClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 홈·탭·섹션 응답 상품의 센터 판매 가능 여부(계약 0.13.0 {@code availability}, 2026-11-23 — 사용자 "조회 때 바로 묻기"). mfcId 를
 * 줬을 때만 product-content 카드 일괄 조회를 한 번(100건씩) 부른다. 실패·시간 초과는 모두 UNKNOWN — 홈은 실패시키지 않는다.
 */
@Service
public class ProductAvailability {

  private static final Logger log = LoggerFactory.getLogger(ProductAvailability.class);

  private final ContentAvailabilityClient content;

  public ProductAvailability(ContentAvailabilityClient content) {
    this.content = content;
  }

  /** 상품 ID → AVAILABLE·SOLD_OUT·UNKNOWN. mfcId 가 없거나 주소 설정이 없으면 null(칸을 비운다). */
  public @Nullable Map<String, String> of(List<String> productIds, @Nullable String mfcId) {
    Map<String, ListAvailability.Result> d = detailed(productIds, mfcId);
    if (d == null) {
      return null;
    }
    Map<String, String> out = new HashMap<>();
    d.forEach((k, v) -> out.put(k, v.state()));
    return out;
  }

  /** 상품 ID → 판매 가능 여부 + "N개 남음"(BR-100 R7). mfcId 가 없으면 null. */
  public @Nullable Map<String, ListAvailability.Result> detailed(
      List<String> productIds, @Nullable String mfcId) {
    if (mfcId == null || mfcId.isBlank() || !content.enabled()) {
      return null;
    }
    Map<String, ListAvailability.Result> out = new HashMap<>();
    if (productIds.isEmpty()) {
      return out;
    }
    Map<String, List<ListAvailability.Option>> options;
    try {
      options = content.options(productIds, mfcId);
    } catch (RuntimeException e) {
      // 상품 ID·센터는 남기지 않는다(예외 종류만)
      log.warn("목록 가용 조회 실패 — UNKNOWN 으로: {}", e.getClass().getSimpleName());
      options = Map.of();
    }
    for (String id : productIds) {
      List<ListAvailability.Option> o = options.get(id);
      out.put(
          id,
          o == null
              ? new ListAvailability.Result(ListAvailability.UNKNOWN, null)
              : new ListAvailability.Result(
                  ListAvailability.of(o), ListAvailability.lowStockQty(o)));
    }
    return out;
  }
}
