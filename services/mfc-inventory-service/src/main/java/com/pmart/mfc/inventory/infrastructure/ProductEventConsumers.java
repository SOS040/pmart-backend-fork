package com.pmart.mfc.inventory.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inventory.application.Locations;
import com.pmart.mfc.inventory.application.SkuProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * product-service 의 SkuIssued·ProductVersionPublished 구독(토픽 commerce.product.v1). 투영 반영과 inbox 기록이
 * 한 트랜잭션이고, 리스너가 정상 반환한 뒤 offset 을 커밋한다(ADR-0009). 같은 토픽의 다른 사건은 반영 없이 inbox 에만 남는다. 취급 레코드는 이벤트로
 * 만들지 않는다(레코드 없음 = OFF).
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ProductEventConsumers {

  /** commerce.product.v1 — 바인딩 productEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer productEvents(EventConsumers consumers, SkuProjector projector) {
    return consumers.of(projector::apply);
  }

  /** mfc.location.v1 — 바인딩 locationEvents-in-0(설정 저장소). 위치 투영(ADR-0040 2단계). */
  @Bean
  PmartEventConsumer locationEvents(EventConsumers consumers, Locations locations) {
    return consumers.of(locations::apply);
  }
}
