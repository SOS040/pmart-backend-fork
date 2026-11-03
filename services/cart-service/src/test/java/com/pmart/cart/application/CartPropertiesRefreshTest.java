package com.pmart.cart.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.annotation.Configuration;

/** 실행 중 갱신(ADR-0033): 설정이 바뀌고 refresh 가 오면 같은 빈 참조에서 새 값이 나온다. */
class CartPropertiesRefreshTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CartProperties.class)
  static class Props {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class, RefreshAutoConfiguration.class))
          .withUserConfiguration(Props.class)
          .withPropertyValues("pmart.cart.retention=30d");

  @Test
  void retentionIsReadAgainAfterRefresh() {
    runner.run(
        ctx -> {
          CartProperties props = ctx.getBean(CartProperties.class);
          assertThat(AopUtils.isAopProxy(props)).as("refresh 범위 프록시").isTrue();
          assertThat(props.retention()).isEqualTo(Duration.ofDays(30));

          TestPropertyValues.of("pmart.cart.retention=7d").applyTo(ctx.getEnvironment());
          ctx.getBean(RefreshScope.class).refreshAll();

          // 같은 참조(서비스가 주입받은 것)에서 새 값
          assertThat(props.retention()).isEqualTo(Duration.ofDays(7));
          assertThat(props.productBaseUrl()).isEqualTo("http://product-service:8101");
        });
  }
}
