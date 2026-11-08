package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inbound.application.ReplenishmentTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-inventory 사건 구독(mfc.inventory.v1) — 재고 변동 때 자동 보충 점검 표시. 바인딩 inventoryEvents-in-0(설정 저장소,
 * ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class InventoryEventConsumers {

  @Bean
  PmartEventConsumer inventoryEvents(EventConsumers consumers, ReplenishmentTrigger trigger) {
    return consumers.of(trigger::onInventoryEvent);
  }
}
