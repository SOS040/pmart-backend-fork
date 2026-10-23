package com.pmart.common.test;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 테스트용 이벤트 봉투 빌더. 계약 적합성은 {@code EventSchemaValidator} 로 별도 검증한다. */
public final class EventEnvelopeFixtures {

  private static final EventEnvelopeCodec CODEC = EventEnvelopeCodec.withDefaultMapper();

  private EventEnvelopeFixtures() {}

  public static EventEnvelopeCodec codec() {
    return CODEC;
  }

  public static Builder envelope(String eventType) {
    return new Builder(eventType);
  }

  public static final class Builder {
    private UUID eventId = UUID.randomUUID();
    private final String eventType;
    private int schemaVersion = 1;
    private String sourceSystem = "test-service";
    private String aggregateType = "Test";
    private String aggregateId = "agg-1";
    private long aggregateVersion = 1;
    private Instant occurredAt = Instant.parse("2026-10-23T03:00:00Z");
    private String traceId = "trace-test";
    private String correlationId = "corr-test";
    private EventEnvelope.EventSource source = EventEnvelope.EventSource.REAL;
    private JsonNode payload = CODEC.mapper().createObjectNode();

    private Builder(String eventType) {
      this.eventType = eventType;
    }

    public Builder eventId(UUID v) {
      this.eventId = v;
      return this;
    }

    public Builder schemaVersion(int v) {
      this.schemaVersion = v;
      return this;
    }

    public Builder from(String sourceSystem) {
      this.sourceSystem = sourceSystem;
      return this;
    }

    public Builder aggregate(String type, String id, long version) {
      this.aggregateType = type;
      this.aggregateId = id;
      this.aggregateVersion = version;
      return this;
    }

    public Builder occurredAt(Instant v) {
      this.occurredAt = v;
      return this;
    }

    public Builder trace(String traceId, String correlationId) {
      this.traceId = traceId;
      this.correlationId = correlationId;
      return this;
    }

    public Builder simulated() {
      this.source = EventEnvelope.EventSource.SIMULATED;
      return this;
    }

    public Builder payload(Map<String, Object> map) {
      this.payload = CODEC.mapper().valueToTree(map);
      return this;
    }

    public Builder payload(Object pojo) {
      this.payload = CODEC.mapper().valueToTree(pojo);
      return this;
    }

    public EventEnvelope build() {
      return new EventEnvelope(
          eventId,
          eventType,
          schemaVersion,
          sourceSystem,
          aggregateType,
          aggregateId,
          aggregateVersion,
          occurredAt,
          EventEnvelope.DEFAULT_TIMEZONE,
          traceId,
          correlationId,
          null,
          null,
          source,
          payload);
    }

    public String buildJson() {
      return CODEC.encode(build());
    }
  }
}
