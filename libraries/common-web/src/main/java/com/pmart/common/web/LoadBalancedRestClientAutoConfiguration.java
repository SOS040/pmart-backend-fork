package com.pmart.common.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;

/**
 * 서비스 간 호출을 Eureka 에 등록된 인스턴스로 보낸다(ADR-0032). {@code eureka.client.enabled=true}(lan 프로필)일 때만.
 *
 * <p>Boot 자동 구성 {@code RestClient.Builder}(서비스가 주입받는 것)에만 붙는다. 외부 API(Toss·Kakao)·Elasticsearch 는
 * {@code RestClient.builder()} 로 따로 만들므로 영향이 없다. 주소 {@code http://<서비스 이름>:<포트>} 의 호스트를 서비스 이름으로
 * 보고, 포트는 등록된 인스턴스의 것으로 바뀐다 — 그래서 local·prod(Eureka 꺼짐)와 같은 주소 설정을 그대로 쓴다.
 */
@AutoConfiguration
@ConditionalOnClass({LoadBalancerClient.class, RestClientCustomizer.class})
@ConditionalOnProperty(name = "eureka.client.enabled", havingValue = "true")
public class LoadBalancedRestClientAutoConfiguration {

  @Bean
  RestClientCustomizer pmartLoadBalancedRestClientCustomizer(
      ObjectProvider<LoadBalancerClient> loadBalancer) {
    return builder -> builder.requestInterceptor(new LoadBalancedInterceptor(loadBalancer));
  }
}
