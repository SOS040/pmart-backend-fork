package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pmart.common.messaging.DeadLetters.Status;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Spring Cloud Stream 업무 소비(ADR-0035)를 실제 Kafka·PostgreSQL 로 확인한다: 설정은 function definition +
 * destination 만 주고 나머지(그룹·재시도·DLQ·커밋)는 {@link StreamBindingDefaultsPostProcessor} 기본값으로.
 *
 * <ul>
 *   <li>정상 처리 + 같은 eventId 재전달은 InboxGuard 가 건너뜀
 *   <li>실패: 1 + 재시도 3회 → {@code {topic}.DLT}(원본 바이트·원래 파티션) + dead_letter OPEN. 토픽 두 개 바인딩은 각자 .DLT
 *   <li>재시도 불가 예외(IllegalArgumentException)는 한 번만 시도
 *   <li>재처리(StreamBridge): 원래 토픽에 같은 키·본문 + pmart-replay-of 로 다시 나가고, 또 실패하면 새 행이 원 행을 가리킨다
 * </ul>
 */
@SpringBootTest(
    classes = StreamConsumerDlqIT.App.class,
    properties = {
      "spring.main.banner-mode=off",
      "spring.application.name=dlq-it",
      "pmart.messaging.domain=test",
      "pmart.messaging.consumer.initial-backoff-ms=100",
      "pmart.messaging.consumer.max-backoff-ms=200",
      "spring.flyway.locations=classpath:db/migration/common",
      "spring.cloud.stream.function.autodetect=false",
      "spring.cloud.function.definition=okEvents;failingEvents;badEvents",
      "spring.cloud.stream.bindings.okEvents-in-0.destination=it.ok.v1",
      "spring.cloud.stream.bindings.failingEvents-in-0.destination=it.fail.v1,it.fail2.v1",
      "spring.cloud.stream.bindings.badEvents-in-0.destination=it.bad.v1"
    })
@Testcontainers
class StreamConsumerDlqIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withCommand("postgres", "-c", "wal_level=logical");

  @Container @ServiceConnection
  // common-test KafkaTestContainer 와 같은 시작 명령(이 라이브러리는 common-test 를 쓰지 않는다) — 기본 명령은 시작 스크립트가
  // 다 복사되기 전에 실행해 부하 때 126 으로 죽는다
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.1.1")
          .withCommand(
              "sh",
              "-c",
              "until grep -q /etc/kafka/docker/run /tmp/testcontainers_start.sh 2>/dev/null; do sleep 0.1; done;"
                  + " exec bash /tmp/testcontainers_start.sh");

  /** Kafka 바인더는 @ServiceConnection 연결 정보가 아니라 이 속성을 읽는다. */
  @DynamicPropertySource
  static void kafkaBootstrap(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  static final List<String> handled = new CopyOnWriteArrayList<>();
  static final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    PmartEventConsumer okEvents(EventConsumers c) {
      return c.of(e -> handled.add(e.eventId().toString()));
    }

    @Bean
    PmartEventConsumer failingEvents(EventConsumers c) {
      return c.of(
          e -> {
            attempts
                .computeIfAbsent(e.eventId().toString(), k -> new AtomicInteger())
                .incrementAndGet();
            throw new IllegalStateException("처리 실패(시험)");
          });
    }

    @Bean
    PmartEventConsumer badEvents(EventConsumers c) {
      return c.of(
          e -> {
            attempts
                .computeIfAbsent(e.eventId().toString(), k -> new AtomicInteger())
                .incrementAndGet();
            throw new IllegalArgumentException("잘못된 사건(시험)");
          });
    }
  }

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired EventEnvelopeCodec codec;
  @Autowired DeadLetters deadLetters;
  @Autowired JdbcTemplate jdbc;

  String send(String topic, String key) {
    UUID id = UUID.randomUUID();
    EventEnvelope e =
        new EventEnvelope(
            id,
            "ItHappened",
            1,
            "it-source",
            "It",
            key,
            1,
            Instant.now(),
            "Asia/Seoul",
            "t",
            "c",
            null,
            null,
            EventEnvelope.EventSource.REAL,
            codec.mapper().valueToTree(Map.of("k", key)));
    kafka.send(topic, key, codec.encode(e)).join();
    return id.toString();
  }

  static List<ConsumerRecord<byte[], byte[]>> readAll(String topic, int atLeast) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
    try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      long until = System.currentTimeMillis() + 30_000;
      while (out.size() < atLeast && System.currentTimeMillis() < until) {
        c.poll(Duration.ofMillis(500)).forEach(out::add);
      }
    }
    return out;
  }

  static String text(byte[] b) {
    return b == null ? null : new String(b, StandardCharsets.UTF_8);
  }

  @Test
  void handlesOnceAndSkipsDuplicateEventId() {
    String id = send("it.ok.v1", "K-1");
    await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(id));

    // 같은 봉투(같은 eventId)를 다시 보내도 처리기는 한 번만
    String json =
        jdbc.queryForObject(
            "SELECT event_id::text FROM inbox_event WHERE event_id = ?::uuid", String.class, id);
    assertThat(json).isEqualTo(id);
    kafka.send("it.ok.v1", "K-1", resend(id)).join();
    String marker = send("it.ok.v1", "K-2"); // 뒤 사건이 처리될 때까지 기다려 재전달이 소비됐음을 보장
    await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(marker));
    assertThat(handled.stream().filter(id::equals)).hasSize(1);
  }

  String resend(String eventId) {
    EventEnvelope e =
        new EventEnvelope(
            UUID.fromString(eventId),
            "ItHappened",
            1,
            "it-source",
            "It",
            "K-1",
            1,
            Instant.now(),
            "Asia/Seoul",
            "t",
            "c",
            null,
            null,
            EventEnvelope.EventSource.REAL,
            codec.mapper().valueToTree(Map.of("k", "K-1")));
    return codec.encode(e);
  }

  @Test
  void failureIsRetriedThenSentToTopicDltAndRecorded() {
    String id = send("it.fail.v1", "F-1");

    List<ConsumerRecord<byte[], byte[]>> dlt = readAll("it.fail.v1.DLT", 1);
    assertThat(dlt).hasSize(1);
    ConsumerRecord<byte[], byte[]> r = dlt.get(0);
    assertThat(text(r.key())).isEqualTo("F-1");
    assertThat(text(r.value())).contains(id);
    assertThat(r.partition()).isZero();
    assertThat(attempts.get(id).get()).isEqualTo(4); // 1 + 재시도 3회

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> !deadLetters.list(Status.OPEN, "it.fail.v1", 50).isEmpty());
    var row = deadLetters.list(Status.OPEN, "it.fail.v1", 50).get(0);
    assertThat(row.eventId()).isEqualTo(id);
    assertThat(row.key()).isEqualTo("F-1");
    assertThat(row.exceptionClass()).isEqualTo(IllegalStateException.class.getName());

    // 같은 바인딩의 두 번째 토픽은 자기 .DLT 로
    String id2 = send("it.fail2.v1", "F-2");
    List<ConsumerRecord<byte[], byte[]>> dlt2 = readAll("it.fail2.v1.DLT", 1);
    assertThat(dlt2).hasSize(1);
    assertThat(text(dlt2.get(0).value())).contains(id2);
  }

  @Test
  void nonRetryableExceptionGoesToDltAfterOneAttempt() {
    String id = send("it.bad.v1", "B-1");
    List<ConsumerRecord<byte[], byte[]>> dlt = readAll("it.bad.v1.DLT", 1);
    assertThat(dlt).hasSize(1);
    assertThat(text(dlt.get(0).value())).contains(id);
    assertThat(attempts.get(id).get()).isEqualTo(1);
  }

  @Test
  void replayRepublishesToOriginalTopicWithReplayHeader() {
    String id = send("it.fail.v1", "R-1");
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                deadLetters.list(Status.OPEN, "it.fail.v1", 50).stream()
                    .anyMatch(s -> id.equals(s.eventId())));
    UUID rowId =
        deadLetters.list(Status.OPEN, "it.fail.v1", 50).stream()
            .filter(s -> id.equals(s.eventId()))
            .findFirst()
            .orElseThrow()
            .id();

    assertThat(deadLetters.replay(rowId, "ops-it").status()).isEqualTo(Status.REPLAYED);

    // 원래 토픽에 같은 키·본문 + pmart-replay-of 로 다시 나갔다
    List<ConsumerRecord<byte[], byte[]>> all = readAll("it.fail.v1", 2);
    ConsumerRecord<byte[], byte[]> replayed =
        all.stream()
            .filter(r -> r.headers().lastHeader(DeadLetters.REPLAY_OF_HEADER) != null)
            .filter(r -> text(r.value()).contains(id))
            .findFirst()
            .orElseThrow();
    assertThat(text(replayed.key())).isEqualTo("R-1");
    assertThat(text(replayed.headers().lastHeader(DeadLetters.REPLAY_OF_HEADER).value()))
        .isEqualTo(rowId.toString());

    // 또 실패하면 새 행이 원 행을 가리킨다
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                deadLetters.list(Status.OPEN, "it.fail.v1", 50).stream()
                    .anyMatch(s -> rowId.equals(s.replayOf())));
  }
}
