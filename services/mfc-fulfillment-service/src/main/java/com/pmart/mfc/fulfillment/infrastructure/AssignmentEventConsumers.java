package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.fulfillment.application.TaskService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-capacity 의 AssignmentPublished 구독(토픽 mfc.capacity.v1, 슬라이스 6b). 담당 투영 반영과 inbox 기록이 한 트랜잭션이다.
 * 같은 토픽의 다른 사건은 반영 없이 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 바인딩 {@code assignmentEvents-in-0} 의 토픽은 설정 저장소 {@code
 * mfc-fulfillment-service.yml}.
 */
@Configuration(proxyBeanMethods = false)
class AssignmentEventConsumers {

  /**
   * mfc-inventory 사건(mfc.inventory.v1, 바인딩 inventoryEvents-in-0) — ReservationBlocked 만 진행 중 피킹 점검
   * 표시(#5).
   */
  @Bean
  PmartEventConsumer inventoryEvents(EventConsumers consumers, TaskService tasks) {
    return consumers.of(tasks::onInventoryEvent);
  }

  @Bean
  PmartEventConsumer assignmentEvents(EventConsumers consumers, TaskService tasks) {
    return consumers.of(
        e -> {
          if ("AssignmentPublished".equals(e.eventType())) {
            tasks.applyAssignment(e);
          }
        });
  }
}
