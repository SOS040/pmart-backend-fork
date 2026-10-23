package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OutboxWriterTest {

  private final EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @AfterEach
  void cleanup() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private EventEnvelope envelope() {
    return new EventEnvelope(
        UUID.randomUUID(),
        "OrderCancelled",
        1,
        "order-service",
        "Order",
        "ord-1",
        4,
        Instant.parse("2026-10-23T03:00:00Z"),
        "Asia/Seoul",
        "t-1",
        "c-1",
        null,
        null,
        EventEnvelope.EventSource.REAL,
        codec
            .mapper()
            .valueToTree(
                Map.of("orderId", "ord-1", "scope", "FULL", "lines", java.util.List.of())));
  }

  @Test
  void refusesToWriteOutsideTransaction() {
    OutboxWriter w =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.OFF, codec),
            "order",
            true);
    assertThatThrownBy(() -> w.append(envelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("활성 트랜잭션");
    verify(jdbc, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void insertsThenDeletesInsideTransactionWithComputedTopic() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    OutboxWriter w =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.OFF, codec),
            "order",
            true);
    EventEnvelope e = envelope();

    String topic = w.append(e);

    assertThat(topic).isEqualTo("order.order.v1");
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.startsWith("INSERT INTO outbox_event"),
            eq(e.eventId()),
            eq("Order"),
            eq("ord-1"),
            eq("OrderCancelled"),
            eq("order.order.v1"),
            any(),
            eq("t-1"),
            eq("c-1"),
            any());
    verify(jdbc).update(eq("DELETE FROM outbox_event WHERE id = ?"), eq(e.eventId()));
  }

  @Test
  void enforceModeBlocksContractViolationBeforeInsert() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    OutboxWriter w =
        new OutboxWriter(
            jdbc,
            codec,
            new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, codec),
            "order",
            true);
    EventEnvelope bad =
        envelope(); // OrderCancelled 스키마는 orderId·scope·lines·reason 등 필수 → reason 누락
    assertThatThrownBy(() -> w.append(bad))
        .isInstanceOf(EventSchemaValidator.EventSchemaViolation.class);
    verify(jdbc, never()).update(any(String.class), any(Object[].class));
  }
}
