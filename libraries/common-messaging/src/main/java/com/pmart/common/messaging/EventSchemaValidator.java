package com.pmart.common.messaging;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 봉투+payload 를 contracts/events/{eventType}.v{n}.json 으로 검증한다 (jar 의 pmart-events/ 사본 사용).
 *
 * <p>모드: ENFORCE(위반 시 예외) / WARN(로그만) / OFF. 발행(Outbox 쓰기) 시 ENFORCE 가 기본 — 계약에 맞지 않는 이벤트는 저장소에
 * 들어가지 않는다. 소비 시에는 WARN 을 권장(발행자가 필드를 추가한 신버전을 거절하지 않기 위해).
 *
 * <p>구현: networknt json-schema-validator 3.x (Jackson 3). 스키마 {@code $id} 의 접두사 {@code
 * https://contracts.pmart.local/events/} 를 classpath {@code pmart-events/} 로 매핑해 {@code $ref}
 * (_envelope.v1.json, _defs.v1.json)를 해석한다.
 */
public class EventSchemaValidator {

  public enum Mode {
    ENFORCE,
    WARN,
    OFF
  }

  public static class EventSchemaViolation extends RuntimeException {
    private final List<String> errors;

    public EventSchemaViolation(String eventType, int version, List<String> errors) {
      super("이벤트 계약 위반 " + eventType + ".v" + version + ": " + String.join("; ", errors));
      this.errors = errors;
    }

    public List<String> errors() {
      return errors;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(EventSchemaValidator.class);
  private static final String RESOURCE_PREFIX = "pmart-events/";
  private static final String ID_BASE = "https://contracts.pmart.local/events/";

  private final Mode mode;
  private final EventEnvelopeCodec codec;
  private final SchemaRegistry registry;
  private final Map<String, Schema> cache = new ConcurrentHashMap<>();

  public EventSchemaValidator(Mode mode, EventEnvelopeCodec codec) {
    this.mode = mode;
    this.codec = codec;
    this.registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemaIdResolvers(
                    resolvers -> resolvers.mapPrefix(ID_BASE, "classpath:" + RESOURCE_PREFIX)));
  }

  public Mode mode() {
    return mode;
  }

  /** 스키마 파일이 jar 에 있는지. 없으면 검증을 건너뛰고(WARN 로그) 발행을 막지 않는다 — 계약 누락은 contractCheck 가 잡는다. */
  public boolean hasSchema(String eventType, int schemaVersion) {
    String path = RESOURCE_PREFIX + eventType + ".v" + schemaVersion + ".json";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      return in != null;
    } catch (Exception e) {
      return false;
    }
  }

  public void validate(EventEnvelope envelope) {
    if (mode == Mode.OFF) {
      return;
    }
    List<String> errors = check(envelope);
    if (errors.isEmpty()) {
      return;
    }
    if (mode == Mode.ENFORCE) {
      throw new EventSchemaViolation(envelope.eventType(), envelope.schemaVersion(), errors);
    }
    log.warn(
        "이벤트 계약 위반(WARN 모드) {}.v{} eventId={}: {}",
        envelope.eventType(),
        envelope.schemaVersion(),
        envelope.eventId(),
        errors);
  }

  /** 위반 목록. 비어 있으면 적합. 스키마 파일이 없으면 빈 목록(검증 불가는 위반이 아님). */
  public List<String> check(EventEnvelope envelope) {
    if (!hasSchema(envelope.eventType(), envelope.schemaVersion())) {
      log.warn(
          "이벤트 스키마 없음: {}.v{} — 검증 생략 (contracts/events 확인)",
          envelope.eventType(),
          envelope.schemaVersion());
      return List.of();
    }
    Schema schema =
        cache.computeIfAbsent(
            envelope.eventType() + ".v" + envelope.schemaVersion(),
            key -> registry.getSchema(SchemaLocation.of(ID_BASE + key + ".json")));
    JsonNode json = codec.toJson(envelope);
    List<Error> errors = schema.validate(json);
    return errors.stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage()).toList();
  }

  /** 참조용: 스키마 id 규칙. */
  public static String schemaId(String eventType, int version) {
    return ID_BASE + eventType + ".v" + version + ".json";
  }
}
