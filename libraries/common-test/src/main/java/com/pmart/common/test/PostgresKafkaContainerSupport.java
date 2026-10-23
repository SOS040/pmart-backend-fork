package com.pmart.common.test;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PostgreSQL + Kafka(KRaft, apache/kafka) 통합 테스트 베이스. {@code @ServiceConnection} 이
 * spring.kafka.bootstrap-servers 를 주입한다. Debezium 커넥터는 포함하지 않는다(E2E 는 deploy/local 로).
 */
@Testcontainers
public abstract class PostgresKafkaContainerSupport extends PostgresContainerSupport {

  public static final String KAFKA_IMAGE = "apache/kafka:4.1.1";

  @Container @ServiceConnection
  protected static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

  static {
    KAFKA.withReuse(true);
  }

  protected static KafkaContainer kafka() {
    return KAFKA;
  }
}
