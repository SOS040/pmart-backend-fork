package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

class EventConsumersTest {

  final EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();
  final InboxGuard inbox = mock(InboxGuard.class);
  final EventConsumers consumers = new EventConsumers(codec, inbox);

  byte[] envelope(UUID id) {
    return codec
        .encode(
            new EventEnvelope(
                id,
                "OrderCancelled",
                1,
                "order-service",
                "Order",
                "ORD-1",
                1,
                Instant.now(),
                "Asia/Seoul",
                "t",
                "c",
                null,
                null,
                EventEnvelope.EventSource.REAL,
                codec.mapper().valueToTree(Map.of("orderId", "ORD-1"))))
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void decodesBytesAndRunsHandlerInsideInbox() {
    List<EventEnvelope> seen = new ArrayList<>();
    when(inbox.process(any(), any()))
        .thenAnswer(
            inv -> {
              ((Runnable) inv.getArgument(1)).run();
              return true;
            });
    UUID id = UUID.randomUUID();

    consumers.of(seen::add).accept(MessageBuilder.withPayload(envelope(id)).build());

    assertThat(seen)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventId()).isEqualTo(id);
              assertThat(e.eventType()).isEqualTo("OrderCancelled");
            });
  }

  @Test
  void duplicateSkippedByInboxDoesNotCallHandler() {
    List<EventEnvelope> seen = new ArrayList<>();
    when(inbox.process(any(), any())).thenReturn(false);

    consumers.of(seen::add).accept(MessageBuilder.withPayload(envelope(UUID.randomUUID())).build());

    assertThat(seen).isEmpty();
  }

  /** 작업 B: 처리기 안에서 MDC correlationId = 봉투 값("c"), 끝나면 이전 값으로. */
  @Test
  void handlerSeesEnvelopeCorrelationIdInMdcAndPreviousIsRestored() {
    List<String> seen = new ArrayList<>();
    when(inbox.process(any(), any()))
        .thenAnswer(
            inv -> {
              ((Runnable) inv.getArgument(1)).run();
              return true;
            });
    org.slf4j.MDC.put("correlationId", "before");
    try {
      consumers
          .of(e -> seen.add(org.slf4j.MDC.get("correlationId")))
          .accept(MessageBuilder.withPayload(envelope(UUID.randomUUID())).build());
      assertThat(seen).containsExactly("c");
      assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("before");
    } finally {
      org.slf4j.MDC.remove("correlationId");
    }
  }
}
