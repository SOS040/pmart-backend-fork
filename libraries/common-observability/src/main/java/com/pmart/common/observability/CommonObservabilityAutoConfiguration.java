package com.pmart.common.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * common-observability auto-configuration. 기본값 주입은 {@link ObservabilityDefaultsPostProcessor}가
 * 담당한다.
 */
@AutoConfiguration
public class CommonObservabilityAutoConfiguration {

  /** 서블릿 웹앱에서만 correlationId 필터 등록. 추적 필터(Micrometer)보다 뒤, 공통 헤더 필터보다 앞. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
  static class ServletFilters {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
      FilterRegistrationBean<CorrelationIdFilter> registration =
          new FilterRegistrationBean<>(new CorrelationIdFilter());
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
      registration.setName("pmartCorrelationIdFilter");
      return registration;
    }
  }
}
