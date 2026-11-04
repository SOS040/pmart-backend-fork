package com.pmart.common.messaging;

import java.sql.Timestamp;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Outbox 쓰기. <b>호출자의 활성 트랜잭션 안에서만</b> 동작한다(업무 변경과 같은 로컬 트랜잭션 — ADR-0009).
 *
 * <p>발행은 Debezium 이 WAL 의 INSERT 를 읽어 수행한다. 기본 설정(delete-after-insert=true)에서는 같은 트랜잭션에서 바로 DELETE
 * 해 테이블을 비워 둔다. INSERT 는 WAL 에 남으므로 Debezium 은 정상 캡처하고, DELETE 는 Outbox Event Router 가 무시한다.
 *
 * <p>저장 전 계약 검증(ENFORCE 기본): 계약에 맞지 않는 이벤트는 저장소에 들어가지 않는다.
 */
public class OutboxWriter {

  private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

  private static final String INSERT =
      "INSERT INTO outbox_event "
          + "(id, aggregatetype, aggregateid, type, topic, payload, trace_id, correlation_id, traceparent,"
          + " created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)";
  private static final String DELETE = "DELETE FROM outbox_event WHERE id = ?";

  private final JdbcTemplate jdbc;
  private final EventEnvelopeCodec codec;
  private final EventSchemaValidator validator;
  private final String domain;
  private final boolean deleteAfterInsert;

  public OutboxWriter(
      JdbcTemplate jdbc,
      EventEnvelopeCodec codec,
      EventSchemaValidator validator,
      String domain,
      boolean deleteAfterInsert) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.codec = Objects.requireNonNull(codec);
    this.validator = Objects.requireNonNull(validator);
    this.domain = Objects.requireNonNull(domain);
    this.deleteAfterInsert = deleteAfterInsert;
  }

  /** 토픽은 {@code {domain}.{aggregate}.v{schemaVersion}} 로 계산한다. */
  public String append(EventEnvelope envelope) {
    return append(envelope, envelope.topic(domain));
  }

  /** 토픽을 직접 지정 (공유 이벤트 등). */
  public String append(EventEnvelope envelope, String topic) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "OutboxWriter.append 는 활성 트랜잭션 안에서만 호출한다 (업무 변경과 Outbox 저장의 원자성).");
    }
    validator.validate(envelope);
    String json = codec.encode(envelope);
    jdbc.update(
        INSERT,
        envelope.eventId(),
        envelope.aggregateType(),
        envelope.aggregateId(),
        envelope.eventType(),
        topic,
        json,
        envelope.traceId().isEmpty() ? null : envelope.traceId(),
        envelope.correlationId(),
        currentTraceparent(),
        Timestamp.from(envelope.occurredAt()));
    if (deleteAfterInsert) {
      jdbc.update(DELETE, envelope.eventId());
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "outbox appended {} {} aggregate={}:{} topic={}",
          envelope.eventType(),
          envelope.eventId(),
          envelope.aggregateType(),
          envelope.aggregateId(),
          topic);
    }
    return topic;
  }

  /**
   * 지금 span 의 W3C traceparent(작업 B). Micrometer Tracing 이 MDC 에 넣는 traceId(32 hex)·spanId(16 hex)로
   * 만든다 — 이 라이브러리에 추적 의존성을 두지 않기 위해. 표본은 1.0(관측 기본값)이라 sampled 플래그 01. 없거나 형식이 다르면 null.
   */
  static @Nullable String currentTraceparent() {
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");
    if (traceId == null
        || spanId == null
        || !HEX32.matcher(traceId).matches()
        || !HEX16.matcher(spanId).matches()) {
      return null;
    }
    return "00-" + traceId + "-" + spanId + "-01";
  }

  private static final java.util.regex.Pattern HEX32 =
      java.util.regex.Pattern.compile("[0-9a-f]{32}");
  private static final java.util.regex.Pattern HEX16 =
      java.util.regex.Pattern.compile("[0-9a-f]{16}");
}
