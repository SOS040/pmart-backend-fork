package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.common.messaging.DeadLetters.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.SendResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL 에서 미처리 메시지 기록(V0_0_3)·조회·재처리·폐기·지표를 검증한다(ADR-0030). Kafka 발행은 대역. Docker 필요. */
@Testcontainers
class DeadLettersPostgresIT {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withCommand("postgres", "-c", "wal_level=logical");
  }

  static JdbcTemplate jdbc;

  @SuppressWarnings("unchecked")
  final KafkaOperations<Object, Object> kafka = mock(KafkaOperations.class);

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  DeadLetters deadLetters;

  @BeforeAll
  static void migrate() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUser(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration/common").load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM dead_letter");
    reset(kafka);
    deadLetters =
        new DeadLetters(
            jdbc, JsonMapper.builder().build(), kafka, Clock.systemUTC(), meters, false);
  }

  static ConsumerRecord<String, String> record(String topic, long offset, String eventId) {
    ConsumerRecord<String, String> r =
        new ConsumerRecord<>(
            topic,
            2,
            offset,
            "ORD-1",
            "{\"eventId\":\""
                + eventId
                + "\",\"eventType\":\"RefundResult\",\"sourceSystem\":\"payment-service\",\"payload\":{}}");
    r.headers().add("traceparent", "00-abc-def-01".getBytes(StandardCharsets.UTF_8));
    r.headers().add("kafka_dlt-exception-message", "x".getBytes(StandardCharsets.UTF_8));
    return r;
  }

  static Exception failure() {
    return new ListenerExecutionFailedException(
        "listener failed", new IllegalStateException("환불 의도를 찾을 수 없음"));
  }

  double openGauge(String topic) {
    var g = meters.find("pmart.messaging.dead.letters.open").tag("topic", topic).gauge();
    return g == null ? 0 : g.value();
  }

  @Test
  void recordsOncePerRecordWithEnvelopeFieldsAndRootCause() {
    String eventId = UUID.randomUUID().toString();
    deadLetters.record(record("order.payment.v1", 7, eventId), failure());
    deadLetters.record(record("order.payment.v1", 7, eventId), failure()); // 같은 레코드 재전달

    var items = deadLetters.list(Status.OPEN, null, 50);
    assertThat(items).hasSize(1);
    var s = items.get(0);
    assertThat(s.topic()).isEqualTo("order.payment.v1");
    assertThat(s.partition()).isEqualTo(2);
    assertThat(s.offset()).isEqualTo(7);
    assertThat(s.key()).isEqualTo("ORD-1");
    assertThat(s.eventId()).isEqualTo(eventId);
    assertThat(s.eventType()).isEqualTo("RefundResult");
    assertThat(s.sourceSystem()).isEqualTo("payment-service");
    assertThat(s.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
    assertThat(s.exceptionMessage()).isEqualTo("환불 의도를 찾을 수 없음");
    assertThat(openGauge("order.payment.v1")).isEqualTo(1.0);
    assertThat(meters.counter("pmart.messaging.dead.letters", "topic", "order.payment.v1").count())
        .isEqualTo(2.0);

    var detail = deadLetters.find(s.id()).orElseThrow();
    assertThat(detail.payload()).contains(eventId);
    assertThat(detail.headers())
        .containsKey("traceparent")
        .doesNotContainKey("kafka_dlt-exception-message");
  }

  @Test
  void replayRepublishesSameKeyBodyHeadersAndIsIdempotent() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    deadLetters.record(record("order.payment.v1", 8, UUID.randomUUID().toString()), failure());
    UUID id = deadLetters.list(Status.OPEN, null, 50).get(0).id();

    var replayed = deadLetters.replay(id, "ops-1");
    assertThat(replayed.status()).isEqualTo(Status.REPLAYED);
    assertThat(replayed.resolvedBy()).isEqualTo("ops-1");
    assertThat(openGauge("order.payment.v1")).isZero();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ProducerRecord<Object, Object>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(sent.capture());
    ProducerRecord<Object, Object> out = sent.getValue();
    assertThat(out.topic()).isEqualTo("order.payment.v1");
    assertThat(out.key()).isEqualTo("ORD-1");
    assertThat(new String(out.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
        .isEqualTo("00-abc-def-01");
    assertThat(
            new String(
                out.headers().lastHeader(DeadLetters.REPLAY_OF_HEADER).value(),
                StandardCharsets.UTF_8))
        .isEqualTo(id.toString());

    // 두 번째 재처리는 다시 보내지 않는다
    reset(kafka);
    assertThat(deadLetters.replay(id, "ops-2").resolvedBy()).isEqualTo("ops-1");
    verify(kafka, never()).send(any(ProducerRecord.class));

    // 재처리한 메시지가 다시 실패하면 새 행이 원 행을 가리킨다
    ConsumerRecord<String, String> again =
        record("order.payment.v1", 99, UUID.randomUUID().toString());
    again
        .headers()
        .add(DeadLetters.REPLAY_OF_HEADER, id.toString().getBytes(StandardCharsets.UTF_8));
    deadLetters.record(again, failure());
    assertThat(deadLetters.list(Status.OPEN, null, 50).get(0).replayOf()).isEqualTo(id);
  }

  @Test
  void failedPublishLeavesItOpen() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    deadLetters.record(record("order.claim.v1", 1, UUID.randomUUID().toString()), failure());
    UUID id = deadLetters.list(null, "order.claim.v1", 50).get(0).id();

    assertThatThrownBy(() -> deadLetters.replay(id, "ops-1"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(deadLetters.find(id).orElseThrow().summary().status()).isEqualTo(Status.OPEN);
  }

  @Test
  void discardNeedsOpenAndKeepsReason() {
    deadLetters.record(record("order.claim.v1", 2, UUID.randomUUID().toString()), failure());
    UUID id = deadLetters.list(Status.OPEN, null, 50).get(0).id();

    var d = deadLetters.discard(id, "시험 데이터라 버림", "ops-1");
    assertThat(d.status()).isEqualTo(Status.DISCARDED);
    assertThat(d.resolutionNote()).isEqualTo("시험 데이터라 버림");
    assertThat(deadLetters.discard(id, "again", "ops-2").resolvedBy()).isEqualTo("ops-1");
    assertThatThrownBy(() -> deadLetters.replay(id, "ops-1"))
        .isInstanceOf(DeadLetters.StateConflict.class);
  }

  @Test
  void nonEnvelopePayloadIsStillRecorded() {
    deadLetters.record(
        new ConsumerRecord<>("mfc.fulfillment.v1", 0, 3L, null, "not json"), failure());
    var s = deadLetters.list(Status.OPEN, null, 50).get(0);
    assertThat(s.eventId()).isNull();
    assertThat(deadLetters.find(s.id()).orElseThrow().payload()).isEqualTo("not json");
  }
}
