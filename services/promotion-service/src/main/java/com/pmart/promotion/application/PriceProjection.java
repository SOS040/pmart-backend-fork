package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.promotion.infrastructure.DisplayDiscountStore;
import java.time.Clock;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 가격 사본(ADR-0082 4단계): pricing PriceVersionEffective → SKU 기본가. 표시 할인에서 정률·정액 중 가장 큰 1개를 고르려면 기본가가
 * 있어야 한다. 더 오래된 버전은 무시. 표시 할인은 다음 주기({@link DisplayDiscountSync})가 다시 계산한다.
 */
@Service
public class PriceProjection {

  private final DisplayDiscountStore store;
  private final Clock clock;

  public PriceProjection(DisplayDiscountStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public static boolean wanted(EventEnvelope e) {
    return "PriceVersionEffective".equals(e.eventType());
  }

  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    String sku = p.path("sku").asString(null);
    if (sku == null || !p.path("price").isNumber() || !p.path("priceVersion").isNumber()) {
      return;
    }
    store.projectPrice(
        sku, p.path("price").asLong(), p.path("priceVersion").asInt(), clock.instant());
  }
}
