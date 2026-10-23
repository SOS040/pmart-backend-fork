package com.pmart.common.messaging;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 봉투 ↔ JSON. Jackson 3. 필드 추가만 허용하므로 알 수 없는 필드는 무시하고 보존하지 않는다. */
public class EventEnvelopeCodec {

  private final ObjectMapper mapper;

  public EventEnvelopeCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public static EventEnvelopeCodec withDefaultMapper() {
    return new EventEnvelopeCodec(JsonMapper.builder().build());
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public String encode(EventEnvelope e) {
    return mapper.writeValueAsString(toJson(e));
  }

  public ObjectNode toJson(EventEnvelope e) {
    ObjectNode n = mapper.createObjectNode();
    n.put("eventId", e.eventId().toString());
    n.put("eventType", e.eventType());
    n.put("schemaVersion", e.schemaVersion());
    n.put("sourceSystem", e.sourceSystem());
    n.put("aggregateType", e.aggregateType());
    n.put("aggregateId", e.aggregateId());
    n.put("aggregateVersion", e.aggregateVersion());
    n.put("occurredAt", e.occurredAt().toString());
    n.put("timezone", e.timezone());
    n.put("traceId", e.traceId());
    if (e.correlationId() != null) {
      n.put("correlationId", e.correlationId());
    }
    if (e.causationId() != null) {
      n.put("causationId", e.causationId());
    }
    if (e.actor() != null) {
      ObjectNode a = n.putObject("actor");
      a.put("type", e.actor().type().name());
      a.put("id", e.actor().id());
    }
    n.put("source", e.source().name());
    n.set("payload", e.payload());
    return n;
  }

  public EventEnvelope decode(String json) {
    return fromJson(mapper.readTree(json));
  }

  public EventEnvelope fromJson(JsonNode n) {
    EventEnvelope.Actor actor = null;
    JsonNode a = n.get("actor");
    if (a != null && a.isObject() && a.hasNonNull("type") && a.hasNonNull("id")) {
      actor =
          new EventEnvelope.Actor(
              EventEnvelope.ActorType.valueOf(a.get("type").asString()), a.get("id").asString());
    }
    JsonNode payload = n.get("payload");
    return new EventEnvelope(
        UUID.fromString(required(n, "eventId")),
        required(n, "eventType"),
        n.path("schemaVersion").asInt(1),
        required(n, "sourceSystem"),
        required(n, "aggregateType"),
        required(n, "aggregateId"),
        n.path("aggregateVersion").asLong(0),
        Instant.parse(required(n, "occurredAt")),
        text(n, "timezone"),
        text(n, "traceId"),
        text(n, "correlationId"),
        text(n, "causationId"),
        actor,
        n.hasNonNull("source")
            ? EventEnvelope.EventSource.valueOf(n.get("source").asString())
            : EventEnvelope.EventSource.REAL,
        payload == null || payload.isNull() ? mapper.createObjectNode() : payload);
  }

  /**
   * 발행용 봉투 생성. traceId/correlationId 는 MDC(관측 라이브러리가 채움)에서 가져온다.
   *
   * @param payloadObject record/POJO/Map/JsonNode — mapper 로 JSON 트리로 변환
   */
  public EventEnvelope create(
      String sourceSystem,
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      EventEnvelope.@Nullable Actor actor,
      EventEnvelope.EventSource source,
      Object payloadObject) {
    JsonNode payload =
        payloadObject instanceof JsonNode jn ? jn : mapper.valueToTree(payloadObject);
    return new EventEnvelope(
        UUID.randomUUID(),
        eventType,
        schemaVersion,
        sourceSystem,
        aggregateType,
        aggregateId,
        aggregateVersion,
        Instant.now(),
        EventEnvelope.DEFAULT_TIMEZONE,
        nullToEmpty(MDC.get("traceId")),
        MDC.get("correlationId"),
        null,
        actor,
        source,
        payload);
  }

  private static String required(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull() || v.asString().isBlank()) {
      throw new IllegalArgumentException("envelope field missing: " + field);
    }
    return v.asString();
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String nullToEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }
}
