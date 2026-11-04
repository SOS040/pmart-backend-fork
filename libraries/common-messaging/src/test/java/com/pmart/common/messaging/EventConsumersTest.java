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
}
