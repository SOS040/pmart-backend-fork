package com.pmart.common.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Kafka 기본값 (가장 낮은 우선순위). 서비스 설정이 항상 이긴다.
 *
 * <ul>
 *   <li>producer: 멱등, acks=all (DLT·직접 발행용. 도메인 이벤트 발행은 Debezium)
 *   <li>consumer: 수동 커밋(리스너 정상 반환 후), earliest, String 역직렬화(봉투는 코드에서 decode)
 *   <li>consumer group = spring.application.name
 * </ul>
 */
public class MessagingDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-messaging-defaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.kafka.producer.acks", "all");
    d.put("spring.kafka.producer.properties.enable.idempotence", "true");
    d.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    d.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");

    d.put("spring.kafka.consumer.group-id", "${spring.application.name:pmart}");
    d.put("spring.kafka.consumer.enable-auto-commit", "false");
    d.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    d.put(
        "spring.kafka.consumer.key-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    d.put(
        "spring.kafka.consumer.value-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    d.put("spring.kafka.consumer.properties.isolation.level", "read_committed");
    d.put("spring.kafka.listener.ack-mode", "RECORD");
    d.put("spring.kafka.listener.missing-topics-fatal", "false");

    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
