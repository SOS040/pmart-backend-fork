package com.pmart.review.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 리뷰 기한 판정 시각(UTC). 시험은 이 빈을 바꿔 기한을 재현할 수 있다. */
@Configuration(proxyBeanMethods = false)
class ReviewConfig {

  @Bean
  Clock reviewClock() {
    return Clock.systemUTC();
  }
}
