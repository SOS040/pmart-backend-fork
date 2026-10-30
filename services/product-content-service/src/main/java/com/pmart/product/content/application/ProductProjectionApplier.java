package com.pmart.product.content.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.product.content.infrastructure.CatalogStore;
import com.pmart.product.content.infrastructure.CatalogStore.ProjectionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ProductVersionPublished 투영(product-service 사본). 조회용 사본만 갱신하고(더 새 버전일 때만), {@code sources[]} 의 원천
 * 키로 콘텐츠 초안을 상품에 연결한다. inbox 트랜잭션 안에서 호출된다.
 */
@Service
public class ProductProjectionApplier {

  private static final Logger log = LoggerFactory.getLogger(ProductProjectionApplier.class);

  private final CatalogStore catalog;
  private final ContentDraftService drafts;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProductProjectionApplier(
      CatalogStore catalog, ContentDraftService drafts, ObjectMapper mapper, Clock clock) {
    this.catalog = catalog;
    this.drafts = drafts;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void handle(EventEnvelope envelope) {
    if (!"ProductVersionPublished".equals(envelope.eventType())) {
      return; // 같은 토픽의 다른 사건(자기 ProductContentPublished 포함)은 반영하지 않는다
    }
    JsonNode p = mapper.valueToTree(envelope.payload());
    String productId = p.path("productId").asString(null);
    if (productId == null || productId.isBlank() || !p.hasNonNull("version")) {
      log.warn("ProductVersionPublished {} without productId/version skipped", envelope.eventId());
      return;
    }
    Instant at = clock.instant();
    List<Map<String, Object>> skus = new ArrayList<>();
    for (JsonNode s : p.path("skus")) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", s.path("sku").asString(null));
      m.put("unitType", s.path("unitType").asString(null));
      m.put("unitsPerSku", s.hasNonNull("unitsPerSku") ? s.get("unitsPerSku").asInt() : null);
      skus.add(m);
    }
    List<String> paths = new ArrayList<>();
    p.path("categoryPaths").forEach(c -> paths.add(c.asString()));
    Instant effective = null;
    if (p.hasNonNull("effectiveAt")) {
      try {
        effective = Instant.parse(p.get("effectiveAt").asString());
      } catch (RuntimeException ignored) {
        effective = null;
      }
    }
    catalog.upsertProjection(
        new ProjectionRow(
            productId,
            p.get("version").asInt(),
            p.path("saleState").asString(null),
            p.path("primaryCategoryId").asString(null),
            mapper.writeValueAsString(paths),
            mapper.writeValueAsString(skus),
            effective,
            at));
    int linked = 0;
    for (JsonNode s : p.path("sources")) {
      String system = s.path("system").asString(null);
      String key = s.path("key").asString(null);
      if (system == null || key == null || system.isBlank() || key.isBlank()) {
        continue;
      }
      catalog.upsertSourceRef(system, key, productId, at);
      linked += drafts.link(system, key, productId);
    }
    log.info(
        "product {} v{} projected, {} content draft(s) linked",
        productId,
        p.get("version").asInt(),
        linked);
  }
}
