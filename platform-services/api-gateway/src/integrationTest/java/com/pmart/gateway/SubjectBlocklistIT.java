package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * 주체 차단(ADR-0054 ⑤) 끝까지: Kafka 에 {@code SubjectBlocked}(Debezium 이 내보내는 모양 — 값 = 봉투 JSON 문자열, 키 =
 * 주체) → 게이트웨이 함수 {@code subjectBlocks} 가 Redis 에 기록 → 차단 전에 발급된 토큰은 401 {@code SUBJECT_BLOCKED}, 차단
 * 뒤 토큰·다른 주체·다른 발급자는 통과. 남길 기간이 이미 지난 차단은 쓰지 않는다(토픽을 다시 읽어도 되살아나지 않음).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "management.tracing.export.enabled=false",
      "spring.data.redis.url=", // it-env 의 ${PMART_REDIS_URL} 대신 @ServiceConnection(RateLimitIT 와
      // 같음)
      "pmart.gateway.rate-limit.enabled=false",
      // it-env 가 끈 것을 다시 켠다
      "pmart.gateway.subject-blocklist.enabled=true",
      "pmart.gateway.subject-blocklist.max-token-lifetime=PT2H",
      "spring.cloud.function.definition=subjectBlocks"
    })
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {"pmart.gateway.upstream.order-service"}))
class SubjectBlocklistIT {

  static final String TOPIC = "platform.subject-block.v1";
  static final String ISS = "http://auth-service.test";

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  // common-test KafkaTestContainer 와 같은 이미지·시작 명령(게이트웨이는 common-test 를 쓰지 않는다 — common-web 을 끌고 온다)
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.1.1")
          .withCommand(
              "sh",
              "-c",
              "until grep -q /etc/kafka/docker/run /tmp/testcontainers_start.sh 2>/dev/null; do"
                  + " sleep 0.1; done; exec bash /tmp/testcontainers_start.sh");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired WebTestClient client;
  @Autowired StringRedisTemplate redis;

  @InjectWireMock("upstream")
  WireMockServer upstream;

  @BeforeEach
  void stubUpstream() {
    upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
  }

  @Test
  void tokensIssuedBeforeBlockAreRejectedAfterTheEventArrives() throws Exception {
    String subject = "OP-" + UUID.randomUUID();
    Instant blockedAt = Instant.now();
    Instant before = blockedAt.minusSeconds(60);
    Instant after = blockedAt.plusSeconds(5);

    // 사건 전에는 통과
    ordersAs(ISS, subject, before).expectStatus().isOk();

    send(subject, envelope(ISS, subject, blockedAt));
    awaitKey(ISS, subject);

    ordersAs(ISS, subject, before)
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader()
        .contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SUBJECT_BLOCKED");
    // 차단 뒤에 새로 받은 토큰(다시 활성화된 뒤 로그인)은 통과
    ordersAs(ISS, subject, after).expectStatus().isOk();
    // 다른 주체·같은 sub 의 다른 발급자는 상관없다
    ordersAs(ISS, "OP-" + UUID.randomUUID(), before).expectStatus().isOk();
    ordersAs("http://rider-auth-service.test", subject, before).expectStatus().isOk();

    // 같은 사건을 다시 받거나(재전달) 더 이른 차단이 늦게 와도 늦은 시각이 남는다
    send(subject, envelope(ISS, subject, blockedAt.minusSeconds(3600)));
    String other = "OP-" + UUID.randomUUID();
    // 같은 키(=같은 파티션)로 표지 사건 — 이것이 반영되면 앞 사건도 처리됐다
    send(subject, envelope(ISS, other, Instant.now()));
    awaitKey(ISS, other);
    assertThat(redis.opsForValue().get(key(ISS, subject)))
        .isEqualTo(String.valueOf(blockedAt.getEpochSecond()));
  }

  @Test
  void expiredBlocksAreNotWritten() throws Exception {
    String old = "OP-" + UUID.randomUUID();
    String marker = "OP-" + UUID.randomUUID();
    // 3시간 전 차단 = 남길 기간(2시간)이 지났다 → 쓰지 않는다
    send(old, envelope(ISS, old, Instant.now().minus(Duration.ofHours(3))));
    send(old, envelope(ISS, marker, Instant.now())); // 같은 키(=같은 파티션) — 뒤 사건이 보이면 앞 사건도 처리됐다
    awaitKey(ISS, marker);
    assertThat(redis.hasKey(key(ISS, old))).isFalse();
    Long ttl = redis.getExpire(key(ISS, marker));
    assertThat(ttl).isBetween(Duration.ofHours(2).minusMinutes(1).toSeconds(), 7200L);
  }

  private WebTestClient.ResponseSpec ordersAs(String iss, String subject, Instant issuedAt) {
    return client
        .mutateWith(
            mockJwt()
                .jwt(
                    j ->
                        j.subject(subject)
                            .claim("iss", iss)
                            .issuedAt(issuedAt)
                            .expiresAt(Instant.now().plusSeconds(900))))
        .get()
        .uri("/api/v1/orders/ord-1")
        .exchange();
  }

  private static String key(String iss, String subject) {
    return "pmart:gw:subject-block:" + iss + "|" + subject;
  }

  private void awaitKey(String iss, String subject) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(redis.hasKey(key(iss, subject)))) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("차단 사건이 60초 안에 Redis 에 반영되지 않았다");
  }

  private static String envelope(String iss, String subject, Instant blockedBefore) {
    return """
        {"eventId":"%s","eventType":"SubjectBlocked","schemaVersion":1,"sourceSystem":"auth-service",
         "aggregateType":"SubjectBlock","aggregateId":"%s","aggregateVersion":2,
         "occurredAt":"%s","traceId":"","payload":{"issuer":"%s","subject":"%s","userType":"OPERATOR",
         "blockedBefore":"%s","reason":"ACCOUNT_DISABLED"}}
        """
        .formatted(UUID.randomUUID(), subject, blockedBefore, iss, subject, blockedBefore);
  }

  private static void send(String key, String value) throws Exception {
    try (KafkaProducer<byte[], byte[]> producer =
        new KafkaProducer<>(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers()),
            new ByteArraySerializer(),
            new ByteArraySerializer())) {
      producer
          .send(
              new ProducerRecord<>(
                  TOPIC,
                  key.getBytes(StandardCharsets.UTF_8),
                  value.getBytes(StandardCharsets.UTF_8)))
          .get();
    }
  }
}
