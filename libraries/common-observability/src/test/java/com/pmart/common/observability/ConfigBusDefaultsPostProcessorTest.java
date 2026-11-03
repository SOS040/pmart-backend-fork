package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ConfigBusDefaultsPostProcessorTest {

  private final ConfigBusDefaultsPostProcessor processor = new ConfigBusDefaultsPostProcessor();

  @Test
  void busIsOffByDefault() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.cloud.bus.enabled")).isEqualTo("false");
    assertThat(env.getProperty("spring.cloud.stream.function.autodetect")).isEqualTo("false");
    assertThat(env.getProperty("management.health.binders.enabled")).isEqualTo("false");
  }

  @Test
  void busKafkaClientsIgnoreAppWideStringSerializers() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, new SpringApplication());

    String binder = "spring.cloud.stream.kafka.binder.";
    assertThat(env.getProperty(binder + "producer-properties[value.serializer]"))
        .endsWith("ByteArraySerializer");
    assertThat(env.getProperty(binder + "consumer-properties[value.deserializer]"))
        .endsWith("ByteArrayDeserializer");
    // 뜬 뒤의 알림만 받는다(앱 공통 earliest 로 지난 알림을 다시 받지 않게)
    assertThat(env.getProperty(binder + "consumer-properties[auto.offset.reset]"))
        .isEqualTo("latest");
  }

  @Test
  void configRepositoryValueWins() {
    MockEnvironment env = new MockEnvironment();
    // configserver: import(application-lan.yml)는 이 후처리보다 먼저 환경에 들어온다
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("configserver", Map.of("spring.cloud.bus.enabled", "true")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.cloud.bus.enabled")).isEqualTo("true");
  }

  @Test
  void runsBeforeBusEnvironmentPostProcessor() {
    // BusEnvironmentPostProcessor 는 Ordered 가 아니라 LOWEST_PRECEDENCE 로 정렬된다
    assertThat(processor.getOrder()).isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
  }
}
