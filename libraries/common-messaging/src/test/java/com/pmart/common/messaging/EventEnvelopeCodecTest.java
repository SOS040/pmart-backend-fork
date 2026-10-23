package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

class EventEnvelopeCodecTest {

  private final EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void roundTripsAllFields() {
    EventEnvelope e =
        new EventEnvelope(
            UUID.randomUUID(),
            "OrderConfirmed",
            1,
            "order-service",
            "Order",
            "ord-1",
            7,
            Instant.parse("2026-10-23T03:00:00Z"),
            "Asia/Seoul",
            "trace-1",
            "corr-1",
            "cause-1",
            new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, "acc-9"),
            EventEnvelope.EventSource.REAL,
            codec.mapper().valueToTree(Map.of("orderId", "ord-1", "totalAmount", 27100)));

    String json = codec.encode(e);
    EventEnvelope back = codec.decode(json);

    assertThat(back).isEqualTo(e);
    assertThat(json).contains("\"eventType\":\"OrderConfirmed\"").contains("\"source\":\"REAL\"");
  }

  @Test
  void createFillsTraceAndCorrelationFromMdc() {
    MDC.put("traceId", "t-77");
    MDC.put("correlationId", "saga-5");
    EventEnvelope e =
        codec.create(
            "order-service",
            "OrderCancelled",
            1,
            "Order",
            "ord-2",
            3,
            null,
            EventEnvelope.EventSource.REAL,
            Map.of("orderId", "ord-2", "scope", "FULL"));
    assertThat(e.traceId()).isEqualTo("t-77");
    assertThat(e.correlationId()).isEqualTo("saga-5");
    assertThat(e.timezone()).isEqualTo("Asia/Seoul");
    assertThat(e.eventId()).isNotNull();
    JsonNode p = e.payload();
    assertThat(p.get("scope").asString()).isEqualTo("FULL");
  }

  @Test
  void unknownFieldsAreIgnoredForForwardCompatibility() {
    String json =
        """
        {"eventId":"8f0b3e1c-1111-4bbb-8ccc-123456789abc","eventType":"PackageHandedOver","schemaVersion":1,
         "sourceSystem":"mfc-fulfillment-service","aggregateType":"Package","aggregateId":"pkg-1",
         "aggregateVersion":2,"occurredAt":"2026-10-23T04:00:00Z","traceId":"t","futureField":"x",
         "payload":{"packageId":"pkg-1"}}
        """;
    EventEnvelope e = codec.decode(json);
    assertThat(e.eventType()).isEqualTo("PackageHandedOver");
    assertThat(e.source()).isEqualTo(EventEnvelope.EventSource.REAL);
    assertThat(e.correlationId()).isNull();
  }

  @Test
  void rejectsInvalidEventTypeAndMissingFields() {
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    UUID.randomUUID(),
                    "order_confirmed",
                    1,
                    "s",
                    "Order",
                    "1",
                    0,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    codec.mapper().createObjectNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PascalCase");
    assertThatThrownBy(() -> codec.decode("{\"eventType\":\"X\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventId");
  }

  @Test
  void topicFollowsNamingRule() {
    assertThat(Topics.of("order", "Order", 1)).isEqualTo("order.order.v1");
    assertThat(Topics.of("tms", "TransportRequest", 2)).isEqualTo("tms.transport-request.v2");
    assertThat(Topics.deadLetter("order.order.v1")).isEqualTo("order.order.v1.DLT");
    assertThatThrownBy(() -> Topics.of("Order", "Order", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
