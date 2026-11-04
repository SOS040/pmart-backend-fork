package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class StreamBindingDefaultsPostProcessorTest {

  final StreamBindingDefaultsPostProcessor pp = new StreamBindingDefaultsPostProcessor();

  MockEnvironment env(String definition) {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "claim-service");
    if (definition != null) {
      env.setProperty("spring.cloud.function.definition", definition);
    }
    return env;
  }

  @Test
  void fillsBindingDefaultsPerFunctionButNotForBus() {
    MockEnvironment env = env("orderEvents; paymentEvents;busConsumer");
    pp.postProcessEnvironment(env, null);

    String b = "spring.cloud.stream.bindings.orderEvents-in-0.";
    assertThat(env.getProperty(b + "group")).isEqualTo("claim-service");
    assertThat(env.getProperty(b + "consumer.max-attempts")).isEqualTo("4");
    assertThat(env.getProperty(b + "consumer.back-off-initial-interval")).isEqualTo("1000");
    assertThat(env.getProperty(b + "consumer.back-off-max-interval")).isEqualTo("10000");
    assertThat(
            env.getProperty(b + "consumer.retryable-exceptions.java.lang.IllegalArgumentException"))
        .isEqualTo("false");
    String k = "spring.cloud.stream.kafka.bindings.orderEvents-in-0.consumer.";
    assertThat(env.getProperty(k + "enable-dlq")).isEqualTo("true");
    assertThat(env.getProperty(k + "ack-mode")).isEqualTo("RECORD");
    assertThat(env.getProperty(k + "configuration[auto.offset.reset]")).isEqualTo("earliest");
    assertThat(env.getProperty(k + "configuration[isolation.level]")).isEqualTo("read_committed");
    assertThat(env.getProperty(k + "configuration[value.deserializer]"))
        .endsWith("ByteArrayDeserializer");
    assertThat(env.getProperty("spring.cloud.stream.bindings.paymentEvents-in-0.group"))
        .isEqualTo("claim-service");

    // Bus 는 익명 그룹·latest 여야 한다 — 손대지 않음
    assertThat(env.getProperty("spring.cloud.stream.bindings.busConsumer-in-0.group")).isNull();
    assertThat(env.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo("true");
    assertThat(env.getProperty("spring.cloud.stream.default.group")).isNull();
  }

  @Test
  void retrySettingsComeFromMessagingPropertiesAndConfigWins() {
    MockEnvironment env = env("orderEvents");
    env.setProperty("pmart.messaging.consumer.max-retries", "5");
    env.setProperty("spring.cloud.stream.bindings.orderEvents-in-0.group", "custom");
    pp.postProcessEnvironment(env, null);

    assertThat(
            env.getProperty("spring.cloud.stream.bindings.orderEvents-in-0.consumer.max-attempts"))
        .isEqualTo("6");
    assertThat(env.getProperty("spring.cloud.stream.bindings.orderEvents-in-0.group"))
        .isEqualTo("custom");
  }

  @Test
  void runsOnceAndNoBindingsWithoutDefinition() {
    MockEnvironment env = env(null);
    pp.postProcessEnvironment(env, null);
    pp.postProcessEnvironment(env, null);
    assertThat(
            env.getPropertySources().stream()
                .filter(s -> s.getName().equals(StreamBindingDefaultsPostProcessor.SOURCE_NAME))
                .count())
        .isEqualTo(1);
    var source =
        (MapPropertySource)
            env.getPropertySources().get(StreamBindingDefaultsPostProcessor.SOURCE_NAME);
    assertThat(source.getPropertyNames())
        .noneMatch(n -> n.startsWith("spring.cloud.stream.bindings."));
  }

  @Test
  void verifierRejectsConsumerWithoutBinding() {
    MockEnvironment env = env("orderEvents");
    env.setProperty("spring.cloud.stream.bindings.orderEvents-in-0.destination", "order.order.v1");

    ConsumerBindingVerifier.verify(new String[] {"orderEvents"}, env); // 통과

    assertThatThrownBy(
            () ->
                ConsumerBindingVerifier.verify(new String[] {"orderEvents", "paymentEvents"}, env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paymentEvents: spring.cloud.function.definition 에 없음");

    MockEnvironment noDest = env("orderEvents");
    assertThatThrownBy(() -> ConsumerBindingVerifier.verify(new String[] {"orderEvents"}, noDest))
        .hasMessageContaining("orderEvents-in-0.destination 없음");
  }

  @Test
  void functionsParsing() {
    assertThat(StreamBindingDefaultsPostProcessor.functions(" a ;b;;busConsumer "))
        .containsExactly("a", "b");
    assertThat(StreamBindingDefaultsPostProcessor.functions(null)).isEmpty();
  }
}
