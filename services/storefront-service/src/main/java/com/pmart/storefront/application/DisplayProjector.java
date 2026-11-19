package com.pmart.storefront.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.storefront.domain.DefaultLayout;
import com.pmart.storefront.infrastructure.ContentCardClient;
import com.pmart.storefront.infrastructure.DisplayStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 상품·콘텐츠·판매 상태·가격 사건 → 진열 투영. inbox 트랜잭션 안에서 실행된다. 콘텐츠 사건에는 이름·이미지가 없어 공개 카드 API 로 보강하고(실패하면 예외 →
 * 재처리), 게시본이 없으면 건너뛴다.
 */
@Service
public class DisplayProjector {

  private static final Logger log = LoggerFactory.getLogger(DisplayProjector.class);

  private final DisplayStore store;
  private final ContentCardClient cards;
  private final Clock clock;

  public DisplayProjector(DisplayStore store, ContentCardClient cards, Clock clock) {
    this.store = store;
    this.cards = cards;
    this.clock = clock;
  }

  /**
   * mfc.sku-handling.v1 SkuHandlingChanged(BR-49) — 센터 취급 사본. 재발행(republished)도 같은 규칙(version 이 같으면
   * 바뀌지 않음).
   */
  public void applyHandling(EventEnvelope e) {
    if (!"SkuHandlingChanged".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String mfcId = p.path("mfcId").asString(null);
    String sku = p.path("sku").asString(null);
    if (mfcId == null || sku == null) {
      log.warn("SkuHandlingChanged {} 에 센터·SKU 가 없다 — 건너뜀", e.eventId());
      return;
    }
    store.upsertHandling(
        mfcId,
        sku,
        p.path("handlingEnabled").asBoolean(false),
        p.path("version").asInt(0),
        at(p.path("changedAt"), e));
  }

  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    switch (e.eventType()) {
      case "ProductVersionPublished" ->
          store.upsertProduct(
              p.path("productId").asString(),
              p.path("version").asInt(),
              p.path("skus").path(0).path("sku").asString(null),
              p.path("saleState").asString("ON_SALE"),
              rowVersion(p.path("rowVersion")),
              at(p.path("effectiveAt"), e),
              clock.instant(),
              // 대표 SKU 내용량(2026-11-19 BR-19). 없으면 null(옛 사건·개수로 파는 상품)
              p.path("skus").path(0).path("netContent").path("amount").isNumber()
                  ? p.path("skus").path(0).path("netContent").path("amount").asInt()
                  : null,
              p.path("skus").path(0).path("netContent").path("unit").asString(null));
      case "ProductContentPublished" -> {
        String productId = p.path("productId").asString();
        cards
            .card(productId)
            .ifPresentOrElse(
                c ->
                    store.upsertContent(
                        productId,
                        Math.max(c.contentVersion(), p.path("contentVersion").asInt()),
                        c.name(),
                        c.imageUrl(),
                        at(p.path("publishedAt"), e),
                        clock.instant()),
                () -> log.warn("콘텐츠 사건을 받았지만 게시본이 없다 {} — 건너뜀", productId));
      }
      case "ProductSaleStateChanged" ->
          store.upsertSaleState(
              p.path("productId").asString(),
              DefaultLayout.saleStateFromEvent(p.path("state").asString("ON_SALE")),
              rowVersion(p.path("version")),
              at(p.path("effectiveAt"), e),
              clock.instant());
      case "PriceVersionEffective" ->
          store.upsertPrice(
              p.path("sku").asString(),
              p.path("price").asLong(),
              p.path("priceVersion").asInt(),
              at(p.path("effectiveAt"), e));
      default -> {
        // 진열에 쓰지 않는 사건(SkuIssued·CategoryChanged 등)
      }
    }
  }

  /** 상품 행 버전(PVP rowVersion·PSSC version). 옛 사건에는 없다(null). */
  private static @org.jspecify.annotations.Nullable Integer rowVersion(JsonNode n) {
    return n.isNumber() ? n.asInt() : null;
  }

  private static Instant at(JsonNode n, EventEnvelope e) {
    return n.isString() ? Instant.parse(n.asString()) : e.occurredAt();
  }
}
