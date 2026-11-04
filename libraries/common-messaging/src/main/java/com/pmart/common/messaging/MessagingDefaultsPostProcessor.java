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
 *   <li>producer: 멱등, acks=all, String 직렬화 (Boot KafkaTemplate — 운영 코드는 쓰지 않고 통합 시험이 이벤트를 넣을 때 쓴다.
 *       도메인 이벤트 발행은 Debezium, DLT·재처리는 Spring Cloud Stream 바인더 — ADR-0035)
 *   <li>consumer: 수동 커밋, earliest, String 역직렬화, group = spring.application.name. Kafka 바인더가 이 값을 자기
 *       클라이언트에 합치지만, 업무 바인딩의 직렬화·offset·그룹은 {@link StreamBindingDefaultsPostProcessor} 가 바인딩마다 다시
 *       정한다
 * </ul>
 *
 * <p>Spring Cloud Stream Kafka 바인더(Bus, ADR-0033)의 {@code KafkaBinderEnvironmentPostProcessor} 도 가장
 * 낮은 우선순위에 {@code spring.kafka.producer/consumer} 직렬화기를 {@code ByteArray*} 로 넣는다. 둘 다 맨 뒤에 붙이므로
 * <b>먼저 도는 쪽이 이긴다</b> — 바인더보다 먼저 돌아 String 직렬화를 지킨다(그러지 않으면 Boot 의 KafkaTemplate·리스너가 byte[] 로 바뀐다,
 * 2026-11-03 통합 시험에서 발견). 바인더 자신은 Bus 용 클라이언트에 ByteArray 를 직접 지정해 영향이 없다.
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
    return Ordered.LOWEST_PRECEDENCE
        - 10; // KafkaBinderEnvironmentPostProcessor(순서 없음 = LOWEST)보다 먼저
  }
}
