package com.pmart.common.test;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka(KRaft, apache/kafka)만 띄우는 통합 테스트 베이스. DB 가 없는 서비스(search·fulfillment-routing 등)용.
 *
 * <p>DB 없는 서비스에 {@link PostgresKafkaContainerSupport} 를 쓰면 {@code spring.datasource.*} 가 주입돼, 운영에서
 * DataSource 없이 기동하지 못하는 결함이 테스트에서 가려진다(2026-10-24 LAN 배포에서 발견).
 */
@Testcontainers
public abstract class KafkaContainerSupport {

  @Container @ServiceConnection
  protected static final KafkaContainer KAFKA = KafkaTestContainer.create();

  static {
    KAFKA.withReuse(true);
  }

  protected static KafkaContainer kafka() {
    return KAFKA;
  }
}
