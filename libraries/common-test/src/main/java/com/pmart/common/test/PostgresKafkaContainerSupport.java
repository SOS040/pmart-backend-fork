package com.pmart.common.test;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PostgreSQL + Kafka(KRaft, apache/kafka) 통합 테스트 베이스. {@code @ServiceConnection} 이 Boot Kafka 연결
 * 정보를 주입하고, Spring Cloud Stream Kafka 바인더(업무 소비, ADR-0035)가 읽는 {@code
 * spring.kafka.bootstrap-servers} 도 {@link #kafkaBootstrap} 로 넣는다 — 바인더는 {@code @ServiceConnection}
 * 연결 정보를 쓰지 않고 이 속성으로 {@code localhost:9092} 에 붙으려 했다(2026-11-04 확인). Debezium 커넥터는 포함하지 않는다(E2E 는
 * deploy/local 로).
 */
@Testcontainers
public abstract class PostgresKafkaContainerSupport extends PostgresContainerSupport {

  public static final String KAFKA_IMAGE = "apache/kafka:4.1.1";

  @Container @ServiceConnection
  protected static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

  static {
    KAFKA.withReuse(true);
  }

  @DynamicPropertySource
  static void kafkaBootstrap(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  protected static KafkaContainer kafka() {
    return KAFKA;
  }
}
