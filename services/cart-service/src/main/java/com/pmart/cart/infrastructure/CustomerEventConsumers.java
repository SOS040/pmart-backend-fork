package com.pmart.cart.infrastructure;

import com.pmart.cart.application.CartWithdrawalService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customer-service 이벤트 구독(commerce.customer.v1, ADR-0077). CustomerWithdrawalCompleted 만 받고(회원 장바구니
 * 지움) 나머지는 inbox 에도 남기지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class CustomerEventConsumers {

  /** commerce.customer.v1 — 바인딩 customerEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer customerEvents(EventConsumers consumers, CartWithdrawalService withdrawals) {
    return consumers.of(
        e -> "CustomerWithdrawalCompleted".equals(e.eventType()),
        withdrawals::onWithdrawalCompleted);
  }
}
