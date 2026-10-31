package com.pmart.delivery.serviceability.application;

import com.pmart.delivery.serviceability.infrastructure.kakao.KakaoLocalClient;
import com.pmart.delivery.serviceability.infrastructure.kakao.KakaoProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoProperties.class)
class ServiceabilityConfig {

  /** UTC(ADR-0010). DB(마이크로초)와 응답 값이 같도록 마이크로초로 자른다. */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
  }

  /** 주소 ↔ 좌표(ADR-0023). 키가 없으면 호출마다 CONFIG_REQUIRED. */
  @Bean
  Geocoder geocoder(KakaoProperties props) {
    return new KakaoLocalClient(props);
  }
}
