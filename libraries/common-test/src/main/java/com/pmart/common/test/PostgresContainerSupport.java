package com.pmart.common.test;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 테스트용 PostgreSQL (실제 운영 엔진·논리 복제 설정 동일). H2 로 PostgreSQL 동작을 검증하지 않는다 (testing.md).
 *
 * <pre>
 * {@code @SpringBootTest}
 * class OrderRepositoryIT extends PostgresContainerSupport { ... }
 * </pre>
 *
 * {@code @ServiceConnection} 이 spring.datasource.* 를 자동 주입한다. 컨테이너는 JVM 당 1회 기동되어 테스트 클래스 간 공유된다.
 */
@Testcontainers
public abstract class PostgresContainerSupport {

  public static final String IMAGE = "postgres:17-alpine";

  @Container @ServiceConnection
  protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

  static {
    POSTGRES.withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=5");
    POSTGRES.withReuse(true);
  }

  protected static PostgreSQLContainer postgres() {
    return POSTGRES;
  }
}
