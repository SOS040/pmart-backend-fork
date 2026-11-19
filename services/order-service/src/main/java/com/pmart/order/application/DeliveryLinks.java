package com.pmart.order.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.order.domain.DeliveryLinkRules;
import com.pmart.order.infrastructure.DeliveryLinkStore;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 주문의 배송 짝(2026-11-19, 계약 0.15.0 — 고객 앱 BR-61). delivery.delivery.v1 의 배송
 * 생성(FORWARD·RESHIPMENT)으로 주문 ↔ 배송을 잇고, 배정·픽업·전달·실패·픽업 전 취소로 상태를 앞으로만 옮긴다. 고객 주문 조회 shipments[] 가
 * 읽는다(그래야 앱이 deliveryId 로 배송 현황을 연다).
 */
@Service
public class DeliveryLinks {

  private final DeliveryLinkStore store;
  private final Clock clock;

  public DeliveryLinks(DeliveryLinkStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    DeliveryLinkRules.Step step = DeliveryLinkRules.step(e.eventType());
    JsonNode p = e.payload();
    String deliveryId = p.path("deliveryId").asString("");
    if (DeliveryLinkRules.FAILURE_REVERTED.equals(e.eventType()) && !deliveryId.isBlank()) {
      // O-10(2026-11-19) — 잘못 누른 전달 실패 확정을 되돌림: 같은 라이더가 전달을 이어 간다
      store.revertFailure(
          deliveryId,
          DeliveryLinkRules.REVERTED_TO.state(),
          DeliveryLinkRules.REVERTED_TO.rank(),
          clock.instant());
      return;
    }
    if (step == null || deliveryId.isBlank()) {
      return;
    }
    if ("DeliveryCreated".equals(e.eventType())) {
      String orderId = p.path("orderId").asString("");
      String shipmentId = p.path("shipmentId").asString("");
      String type = p.path("type").asString("FORWARD");
      if (orderId.isBlank() || shipmentId.isBlank() || !DeliveryLinkRules.customerDelivery(type)) {
        return;
      }
      store.created(deliveryId, orderId, shipmentId, type, clock.instant());
      return;
    }
    store.advance(deliveryId, step.state(), step.rank(), clock.instant());
  }

  public List<DeliveryLinkStore.Link> byOrder(String orderId) {
    return store.byOrder(orderId);
  }
}
