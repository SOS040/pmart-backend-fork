package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL(Testcontainers)에서 공통 마이그레이션·Outbox INSERT/DELETE·Inbox 중복 차단을 검증한다. Docker 필요.
 * {@code -PskipIntegration=true} 로 건너뛸 수 있다.
 */
@Testcontainers
class OutboxInboxPostgresIT {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    // Testcontainers 2.x: withCommand 는 GenericContainer 를 반환하므로 체이닝 대신 별도 호출
    postgres.withCommand("postgres", "-c", "wal_level=logical");
  }

  static DataSource dataSource;
  static JdbcTemplate jdbc;
  static TransactionTemplate tx;

  @BeforeAll
  static void migrate() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUser(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    dataSource = ds;
    Flyway.configure().dataSource(ds).locations("classpath:db/migration/common").load().migrate();
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  private static EventEnvelope envelope(EventEnvelopeCodec codec, String eventType, String id) {
    return new EventEnvelope(
        UUID.randomUUID(),
        eventType,
        1,
        "order-service",
        "Order",
        id,
        1,
        Instant.now(),
        "Asia/Seoul",
        "t",
        "c",
        null,
        null,
        EventEnvelope.EventSource.REAL,
        codec.mapper().valueToTree(Map.of("orderId", id)));
  }

  @Test
  void migrationCreatesTablesAndPublication() {
    Integer tables =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('outbox_event','inbox_event')",
            Integer.class);
    assertThat(tables).isEqualTo(2);
    Integer pubs =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_publication WHERE pubname = 'pmart_outbox'", Integer.class);
    assertThat(pubs).isEqualTo(1);
  }

  @Test
  void outboxInsertIsVisibleInWalButRowIsDeletedInSameTransaction() {
    EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
    OutboxWriter writer =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.OFF, codec),
            "order",
            true);
    EventEnvelope e = envelope(codec, "OrderDraftCreated", "ord-it-1");

    String topic = tx.execute(s -> writer.append(e));

    assertThat(topic).isEqualTo("order.order.v1");
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, e.eventId());
    assertThat(rows).isZero(); // delete-after-insert
  }

  @Test
  void outboxKeepsRowWhenDeleteDisabled() {
    EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
    OutboxWriter writer =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.OFF, codec),
            "order",
            false);
    EventEnvelope e = envelope(codec, "OrderDraftCreated", "ord-it-2");
    tx.execute(s -> writer.append(e));
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT aggregatetype, aggregateid, type, topic, payload::text AS payload FROM outbox_event WHERE id = ?",
            e.eventId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("aggregatetype", "Order")
        .containsEntry("type", "OrderDraftCreated")
        .containsEntry("topic", "order.order.v1");
    String payload = rows.get(0).get("payload").toString();
    assertThat(payload)
        .contains("OrderDraftCreated")
        .contains("ord-it-2"); // jsonb 는 공백·키 순서를 정규화한다
    jdbc.update("DELETE FROM outbox_event WHERE id = ?", e.eventId());
  }

  /** 작업 B: V0_0_4 traceparent 열에 지금 span 의 W3C traceparent(MDC traceId·spanId)를 저장한다. */
  @Test
  void outboxStoresTraceparentFromCurrentSpan() {
    EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
    OutboxWriter writer =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.OFF, codec),
            "order",
            false);
    EventEnvelope e = envelope(codec, "OrderDraftCreated", "ord-it-tp");
    org.slf4j.MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    org.slf4j.MDC.put("spanId", "00f067aa0ba902b7");
    try {
      tx.execute(s -> writer.append(e));
    } finally {
      org.slf4j.MDC.remove("traceId");
      org.slf4j.MDC.remove("spanId");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT traceparent FROM outbox_event WHERE id = ?", String.class, e.eventId()))
        .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    jdbc.update("DELETE FROM outbox_event WHERE id = ?", e.eventId());
  }

  @Test
  void inboxProcessesOnceAndRollsBackHandlerFailure() {
    EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
    InboxGuard guard = new InboxGuard(jdbc, tx);
    EventEnvelope e = envelope(codec, "OrderConfirmed", "ord-it-3");
    AtomicInteger calls = new AtomicInteger();

    assertThat(guard.process(e, calls::incrementAndGet)).isTrue();
    assertThat(guard.process(e, calls::incrementAndGet)).isFalse();
    assertThat(calls.get()).isEqualTo(1);

    EventEnvelope failing = envelope(codec, "OrderConfirmed", "ord-it-4");
    try {
      guard.process(
          failing,
          () -> {
            throw new IllegalStateException("handler failed");
          });
    } catch (IllegalStateException expected) {
      // 핸들러 예외는 전파되고 inbox 기록은 롤백
    }
    Integer recorded =
        jdbc.queryForObject(
            "SELECT count(*) FROM inbox_event WHERE event_id = ?",
            Integer.class,
            failing.eventId());
    assertThat(recorded).isZero();
    assertThat(guard.process(failing, calls::incrementAndGet)).isTrue(); // 재전달 시 처리됨
  }
}
