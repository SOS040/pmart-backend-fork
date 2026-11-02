package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 공통 마이그레이션 위치 설정(2026-10-30 LAN 기동 실패 재현): 서비스 V1 이 이미 적용된 DB 에 공통 V0_x 가 나중에 들어와도 적용된다. 설정이 없으면
 * Flyway 가 "ignored" 로 검증에 실패한다.
 */
@Testcontainers
class FlywayCommonLocationPostgresIT {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withCommand("postgres", "-c", "wal_level=logical");
  }

  static PGSimpleDataSource dataSource(String db) {
    JdbcTemplate admin = new JdbcTemplate(dataSourceFor(postgres.getDatabaseName()));
    admin.execute("CREATE DATABASE " + db);
    return dataSourceFor(db);
  }

  static PGSimpleDataSource dataSourceFor(String db) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db));
    ds.setUser(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    return ds;
  }

  /** 서비스 설정(서비스 마이그레이션 위치) + 공통 자동 구성의 customizer. */
  static FluentConfiguration withCommonCustomizer(PGSimpleDataSource ds) {
    FluentConfiguration c =
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/itsvc");
    new CommonMessagingAutoConfiguration.FlywayCommonLocation()
        .pmartCommonMigrationLocation()
        .customize(c);
    return c;
  }

  @Test
  void commonMigrationsAddedAfterServiceV1AreApplied() {
    PGSimpleDataSource ds = dataSource("late_common");
    // 1) 서비스 V1 만 적용된 DB(공통 마이그레이션이 생기기 전 배포)
    Flyway.configure().dataSource(ds).locations("classpath:db/migration/itsvc").load().migrate();

    // 2) 공통 위치가 붙은 새 이미지로 기동
    withCommonCustomizer(ds).load().migrate();

    JdbcTemplate jdbc = new JdbcTemplate(ds);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly("1", "0.0.1", "0.0.2", "0.0.3");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass('request_record') IS NOT NULL", Boolean.class))
        .isTrue();
  }

  @Test
  void withoutOutOfOrderTheSameDatabaseFailsValidation() {
    PGSimpleDataSource ds = dataSource("late_common_strict");
    Flyway.configure().dataSource(ds).locations("classpath:db/migration/itsvc").load().migrate();

    FluentConfiguration strict = withCommonCustomizer(ds).outOfOrder(false);
    assertThatThrownBy(() -> strict.load().migrate()).hasMessageContaining("ignored");
  }
}
