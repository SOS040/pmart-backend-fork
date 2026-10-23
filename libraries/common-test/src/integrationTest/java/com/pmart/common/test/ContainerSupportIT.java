package com.pmart.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/** 베이스 클래스가 실제 PostgreSQL(wal_level=logical)·Kafka 를 띄우고 @ServiceConnection 으로 연결 정보를 주입하는지. */
@SpringBootTest(
    classes = ContainerSupportIT.App.class,
    properties = {"spring.main.banner-mode=off", "spring.application.name=container-support-it"})
class ContainerSupportIT extends PostgresKafkaContainerSupport {

  @SpringBootApplication
  static class App {}

  @Autowired DataSource dataSource;
  @Autowired Environment env;
  @Autowired KafkaAdmin kafkaAdmin;

  @Test
  void postgresIsLogicalReplicationCapable() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SHOW wal_level", String.class)).isEqualTo("logical");
    assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 17");
  }

  @Test
  void kafkaBootstrapIsInjectedAndReachable() {
    assertThat(kafka().getBootstrapServers()).isNotBlank();
    assertThat(kafkaAdmin.getConfigurationProperties().get("bootstrap.servers").toString())
        .contains(kafka().getBootstrapServers().replace("PLAINTEXT://", ""));
    assertThat(kafkaAdmin.describeTopics()).isNotNull(); // 브로커 메타데이터 조회 성공
  }
}
