package com.pmart.common.web;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * common-web auto-configuration. Spring MVC(서블릿) 애플리케이션에서만 활성화된다. 서비스는 의존성 추가만으로 적용되며 {@code
 * pmart.web.*}로 조정한다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(CommonWebProperties.class)
public class CommonWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ProblemDetailWriter problemDetailWriter(ObjectMapper objectMapper) {
    return new ProblemDetailWriter(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemDetailAdvice problemDetailAdvice(ProblemDetailWriter writer) {
    return new ProblemDetailAdvice(writer);
  }

  /** spring-tx(JPA·JDBC)가 있을 때만: 저장 시 낙관적 잠금 실패 → VERSION_CONFLICT. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.dao.OptimisticLockingFailureException")
  static class OptimisticLockConfiguration {
    @Bean
    @ConditionalOnMissingBean
    OptimisticLockAdvice optimisticLockAdvice(ProblemDetailAdvice delegate) {
      return new OptimisticLockAdvice(delegate);
    }
  }

  @Bean
  public FilterRegistrationBean<RequestHeadersFilter> requestHeadersFilter(
      CommonWebProperties properties, ProblemDetailWriter writer) {
    FilterRegistrationBean<RequestHeadersFilter> registration =
        new FilterRegistrationBean<>(new RequestHeadersFilter(properties, writer));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
    registration.setName("pmartRequestHeadersFilter");
    return registration;
  }

  @Bean
  public WebMvcConfigurer commonWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequestHeadersArgumentResolver());
      }
    };
  }
}
