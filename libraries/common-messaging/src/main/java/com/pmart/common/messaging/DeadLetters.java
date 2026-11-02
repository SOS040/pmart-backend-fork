package com.pmart.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 미처리 메시지(dead letter) 기록·재처리·폐기 (ADR-0030). Kafka 오류 처리가 재시도를 다 쓰면 {@link #record} 로 이 서비스 DB 의
 * {@code dead_letter} 에 남긴다({@code {topic}.DLT} 발행과 별개). 같은 레코드(토픽·파티션·오프셋)는 한 번만 남는다.
 *
 * <p>지표: {@code pmart.messaging.dead.letters.open{topic}}(DB 의 OPEN 건수 — 30초마다 다시 센다, 재기동해도 유지),
 * {@code pmart.messaging.dead.letters{topic}}(기록 수). 본문은 로그에 찍지 않는다.
 */
public final class DeadLetters implements AutoCloseable {

  /** 재처리로 다시 발행한 메시지에 붙이는 헤더(원 행 id). 그 메시지가 다시 실패하면 새 행의 replay_of 가 된다. */
  public static final String REPLAY_OF_HEADER = "pmart-replay-of";

  private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
  private static final String DLT_HEADER_PREFIX = "kafka_dlt-";

  public enum Status {
    OPEN,
    REPLAYED,
    DISCARDED
  }

  /** 목록 행(본문 없음). */
  public record Summary(
      UUID id,
      String topic,
      int partition,
      long offset,
      @Nullable String key,
      @Nullable String eventId,
      @Nullable String eventType,
      @Nullable String sourceSystem,
      String exceptionClass,
      @Nullable String exceptionMessage,
      Status status,
      Instant failedAt,
      @Nullable Instant resolvedAt,
      @Nullable String resolvedBy,
      @Nullable String resolutionNote,
      @Nullable UUID replayOf) {}

  /** 상세(본문·헤더 포함). */
  public record Detail(Summary summary, @Nullable String payload, Map<String, String> headers) {}

  /** 상태를 바꿀 수 없을 때(이미 처리됨 등). */
  public static final class StateConflict extends RuntimeException {
    StateConflict(String message) {
      super(message);
    }
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final KafkaOperations<Object, Object> kafka;
  private final Clock clock;
  private final @Nullable MeterRegistry meters;
  private final @Nullable MultiGauge openGauge;
  private final @Nullable ScheduledExecutorService refresher;

  public DeadLetters(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      KafkaOperations<Object, Object> kafka,
      Clock clock,
      @Nullable MeterRegistry meters,
      boolean refreshGauge) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.mapper = Objects.requireNonNull(mapper);
    this.kafka = Objects.requireNonNull(kafka);
    this.clock = Objects.requireNonNull(clock);
    this.meters = meters;
    this.openGauge =
        meters == null
            ? null
            : MultiGauge.builder("pmart.messaging.dead.letters.open")
                .description("처리하지 못하고 남은 메시지 수(dead_letter OPEN)")
                .register(meters);
    if (meters != null && refreshGauge) {
      refresher =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "pmart-dead-letter-gauge");
                t.setDaemon(true);
                return t;
              });
      refresher.scheduleWithFixedDelay(this::refreshGauge, 20, 30, TimeUnit.SECONDS);
    } else {
      refresher = null;
    }
  }

  // ---------- 기록 ----------

  /** 재시도를 다 쓴 레코드를 남긴다. 실패해도 예외를 던지지 않는다(DLT 사본은 이미 있다) — 로그·지표로만 알린다. */
  public void record(ConsumerRecord<?, ?> record, Exception failure) {
    String topic = record.topic();
    if (meters != null) {
      Counter.builder("pmart.messaging.dead.letters")
          .description("dead_letter 로 기록한 메시지 수")
          .tags(Tags.of("topic", topic))
          .register(meters)
          .increment();
    }
    try {
      String payload = record.value() == null ? null : record.value().toString();
      Map<String, String> headers = new LinkedHashMap<>();
      String replayOf = null;
      for (Header h : record.headers()) {
        if (h.key().startsWith(DLT_HEADER_PREFIX) || h.value() == null) {
          continue;
        }
        if (REPLAY_OF_HEADER.equals(h.key())) {
          replayOf = new String(h.value(), StandardCharsets.UTF_8);
        }
        headers.put(h.key(), Base64.getEncoder().encodeToString(h.value()));
      }
      JsonNode env = envelope(payload);
      Throwable cause = rootCause(failure);
      int inserted =
          jdbc.update(
              "INSERT INTO dead_letter (id, topic, record_partition, record_offset, record_key, payload, headers,"
                  + " event_id, event_type, source_system, exception_class, exception_message, status, failed_at, replay_of)"
                  + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?,?,'OPEN',?,?)"
                  + " ON CONFLICT (topic, record_partition, record_offset) DO NOTHING",
              UUID.randomUUID(),
              topic,
              record.partition(),
              record.offset(),
              record.key() == null ? null : record.key().toString(),
              payload,
              mapper.writeValueAsString(headers),
              text(env, "eventId"),
              text(env, "eventType"),
              text(env, "sourceSystem"),
              truncate(cause.getClass().getName(), 300),
              truncate(cause.getMessage(), 2000),
              Timestamp.from(clock.instant()),
              parseUuid(replayOf));
      log.error(
          "미처리 메시지 기록 topic={} partition={} offset={} eventType={} eventId={} 원인={}{}",
          topic,
          record.partition(),
          record.offset(),
          text(env, "eventType"),
          text(env, "eventId"),
          cause.getClass().getSimpleName(),
          inserted == 0 ? " (이미 기록됨)" : "");
      refreshGauge();
    } catch (RuntimeException e) {
      log.error(
          "미처리 메시지를 dead_letter 에 남기지 못함 topic={} partition={} offset={} — {}.DLT 사본만 있다",
          topic,
          record.partition(),
          record.offset(),
          Topics.deadLetter(topic),
          e);
    }
  }

  // ---------- 조회 ----------

  public List<Summary> list(@Nullable Status status, @Nullable String topic, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM dead_letter WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (topic != null && !topic.isBlank()) {
      sql.append(" AND topic = ?");
      args.add(topic);
    }
    sql.append(" ORDER BY failed_at DESC LIMIT ?");
    args.add(Math.max(1, Math.min(limit, 500)));
    return jdbc.query(sql.toString(), (rs, n) -> summary(rs), args.toArray());
  }

  public Optional<Detail> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM dead_letter WHERE id = ?",
            (rs, n) ->
                new Detail(summary(rs), rs.getString("payload"), headers(rs.getString("headers"))),
            id)
        .stream()
        .findFirst();
  }

  // ---------- 처리 ----------

  /**
   * 원래 토픽에 같은 키·본문·헤더로 다시 발행하고 REPLAYED 로 바꾼다. 다른 구독자는 inbox(같은 eventId)로 중복을 버린다(ADR-0009). 이미
   * REPLAYED 면 그대로 돌려준다(멱등). DISCARDED 면 {@link StateConflict}. 발행이 실패하면 OPEN 으로 되돌리고 예외를 던진다.
   */
  public Summary replay(UUID id, @Nullable String actor) {
    Detail d = find(id).orElseThrow(() -> new java.util.NoSuchElementException(id.toString()));
    if (d.summary().status() == Status.REPLAYED) {
      return d.summary();
    }
    if (d.summary().status() != Status.OPEN) {
      throw new StateConflict("이미 " + d.summary().status() + " 입니다.");
    }
    int claimed =
        jdbc.update(
            "UPDATE dead_letter SET status = 'REPLAYED', resolved_at = ?, resolved_by = ?, resolution_note = ?"
                + " WHERE id = ? AND status = 'OPEN'",
            Timestamp.from(clock.instant()),
            actor,
            "원래 토픽에 다시 발행",
            id);
    if (claimed == 0) {
      return find(id).orElseThrow().summary();
    }
    try {
      ProducerRecord<Object, Object> out =
          new ProducerRecord<>(d.summary().topic(), d.summary().key(), d.payload());
      d.headers()
          .forEach(
              (k, v) -> {
                if (!REPLAY_OF_HEADER.equals(k)) {
                  out.headers().add(k, Base64.getDecoder().decode(v));
                }
              });
      out.headers().add(REPLAY_OF_HEADER, id.toString().getBytes(StandardCharsets.UTF_8));
      kafka.send(out).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      jdbc.update(
          "UPDATE dead_letter SET status = 'OPEN', resolved_at = NULL, resolved_by = NULL, resolution_note = NULL"
              + " WHERE id = ? AND status = 'REPLAYED'",
          id);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("다시 발행하지 못했습니다: " + e.getMessage(), e);
    }
    log.info("미처리 메시지 재처리 id={} topic={} by={}", id, d.summary().topic(), actor);
    refreshGauge();
    return find(id).orElseThrow().summary();
  }

  /** 사유와 함께 버린다(OPEN 만). 이미 DISCARDED 면 그대로. */
  public Summary discard(UUID id, String reason, @Nullable String actor) {
    Summary s =
        find(id).orElseThrow(() -> new java.util.NoSuchElementException(id.toString())).summary();
    if (s.status() == Status.DISCARDED) {
      return s;
    }
    int n =
        jdbc.update(
            "UPDATE dead_letter SET status = 'DISCARDED', resolved_at = ?, resolved_by = ?, resolution_note = ?"
                + " WHERE id = ? AND status = 'OPEN'",
            Timestamp.from(clock.instant()),
            actor,
            truncate(reason, 500),
            id);
    if (n == 0) {
      throw new StateConflict("이미 " + find(id).orElseThrow().summary().status() + " 입니다.");
    }
    log.info("미처리 메시지 폐기 id={} topic={} by={}", id, s.topic(), actor);
    refreshGauge();
    return find(id).orElseThrow().summary();
  }

  /** OPEN 건수를 토픽별로 다시 센다(지표). 표가 아직 없으면(마이그레이션 전) 조용히 건너뛴다. */
  void refreshGauge() {
    if (openGauge == null) {
      return;
    }
    try {
      List<MultiGauge.Row<?>> rows = new ArrayList<>();
      jdbc.query(
          "SELECT topic, COUNT(*) c FROM dead_letter WHERE status = 'OPEN' GROUP BY topic",
          rs -> {
            rows.add(MultiGauge.Row.of(Tags.of("topic", rs.getString("topic")), rs.getLong("c")));
          });
      openGauge.register(rows, true);
    } catch (RuntimeException e) {
      log.debug("dead_letter 건수 갱신 실패: {}", e.toString());
    }
  }

  @Override
  public void close() {
    if (refresher != null) {
      refresher.shutdownNow();
    }
  }

  // ---------- 내부 ----------

  private Summary summary(ResultSet rs) throws SQLException {
    Timestamp resolved = rs.getTimestamp("resolved_at");
    String replayOf = rs.getString("replay_of");
    return new Summary(
        UUID.fromString(rs.getString("id")),
        rs.getString("topic"),
        rs.getInt("record_partition"),
        rs.getLong("record_offset"),
        rs.getString("record_key"),
        rs.getString("event_id"),
        rs.getString("event_type"),
        rs.getString("source_system"),
        rs.getString("exception_class"),
        rs.getString("exception_message"),
        Status.valueOf(rs.getString("status")),
        rs.getTimestamp("failed_at").toInstant(),
        resolved == null ? null : resolved.toInstant(),
        rs.getString("resolved_by"),
        rs.getString("resolution_note"),
        replayOf == null ? null : UUID.fromString(replayOf));
  }

  private Map<String, String> headers(String json) {
    Map<String, String> out = new LinkedHashMap<>();
    mapper.readTree(json).properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
    return out;
  }

  private @Nullable JsonNode envelope(@Nullable String payload) {
    if (payload == null) {
      return null;
    }
    try {
      JsonNode n = mapper.readTree(payload);
      return n.isObject() ? n : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static @Nullable String text(@Nullable JsonNode n, String field) {
    return n != null && n.hasNonNull(field) ? truncate(n.get(field).asString(), 96) : null;
  }

  private static Throwable rootCause(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    return c;
  }

  private static @Nullable UUID parseUuid(@Nullable String s) {
    try {
      return s == null ? null : UUID.fromString(s.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static @Nullable String truncate(@Nullable String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
