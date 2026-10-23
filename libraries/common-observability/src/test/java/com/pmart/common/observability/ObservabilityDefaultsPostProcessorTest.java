package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityDefaultsPostProcessorTest {

  private final ObservabilityDefaultsPostProcessor processor =
      new ObservabilityDefaultsPostProcessor();

  @Test
  void addsDefaultsWithLowestPrecedence() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "order-service");

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("logging.config")).isEqualTo("classpath:pmart-logback.xml");
    assertThat(env.getProperty("management.tracing.propagation.type")).isEqualTo("W3C");
    assertThat(env.getProperty("management.tracing.baggage.remote-fields"))
        .contains("correlationId");
    assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo("order-service");
    assertThat(env.getProperty("management.endpoints.web.exposure.include"))
        .isEqualTo("health,info,prometheus,metrics");
    assertThat(env.getPropertySources().get(ObservabilityDefaultsPostProcessor.SOURCE_NAME))
        .isNotNull();
  }

  @Test
  void serviceConfigurationWins() {
    MockEnvironment env = new MockEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "service", Map.of("management.tracing.sampling.probability", "0.1")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
  }
}
