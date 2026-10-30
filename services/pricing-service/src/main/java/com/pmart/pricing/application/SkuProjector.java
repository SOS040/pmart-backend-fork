package com.pmart.pricing.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.pricing.infrastructure.SkuProjectionStore;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 상품 이벤트 → SKU 투영(무효 SKU 검사용 사본). InboxGuard 트랜잭션 안에서 호출된다. */
@Service
public class SkuProjector {

  private final SkuProjectionStore store;
  private final Clock clock;

  public SkuProjector(SkuProjectionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    Instant at = clock.instant();
    switch (e.eventType()) {
      case "SkuIssued" ->
          store.upsertIssued(
              p.path("sku").asString(), text(p, "productId"), p.path("version").asInt(), at);
      case "ProductVersionPublished" -> {
        int version = p.path("version").asInt();
        String productId = text(p, "productId");
        for (JsonNode s : p.path("skus")) {
          String state = "INACTIVE".equals(text(s, "state")) ? "INACTIVE" : "ACTIVE";
          store.upsertSnapshot(s.path("sku").asString(), productId, state, version, at);
        }
      }
      default -> {
        // 같은 토픽의 다른 사건은 쓰지 않는다
      }
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }
}
