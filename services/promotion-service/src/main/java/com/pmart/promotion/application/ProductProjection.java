package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 상품 사본(ADR-0082): product-service ProductVersionPublished → SKU 마다 노출 카테고리(상위 포함). 카테고리 기간 할인 판정용
 * — 행사 기간에 새로 들어온 상품도 그 카테고리 행사를 받는다. 더 오래된 버전은 무시.
 */
@Service
public class ProductProjection {

  private final PromotionStore store;

  public ProductProjection(PromotionStore store) {
    this.store = store;
  }

  public static boolean wanted(EventEnvelope e) {
    return "ProductVersionPublished".equals(e.eventType());
  }

  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    String productId = p.path("productId").asString();
    int version = p.path("version").asInt();
    Set<String> categories = new LinkedHashSet<>();
    p.path("categoryIds")
        .forEach(c -> categories.addAll(PromotionRules.withAncestors(c.asString())));
    if (p.hasNonNull("primaryCategoryId")) {
      categories.addAll(PromotionRules.withAncestors(p.get("primaryCategoryId").asString()));
    }
    for (JsonNode s : p.path("skus")) {
      store.projectSku(s.path("sku").asString(), productId, version, categories);
    }
  }
}
