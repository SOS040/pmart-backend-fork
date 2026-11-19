package com.pmart.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.KafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 동의어 승인 반영(ADR-0073 단계 B) — 실제 Kafka·Elasticsearch(nori). approval-service 가 낼
 * ApprovalDecided(SEARCH_SYNONYM)를 시험이 platform.approval.v1 에 넣고, search-service 가 기준 버전을 비교해 동의어
 * 집합·규칙 색인을 바꾸고 ApplicationReceiptRecorded 를 platform.approval-receipt.v1 로 회신하는지 본다. 바인딩은 설정 저장소에
 * 넣기 전이라 이 클래스 properties 로.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.cloud.function.definition=approvalEvents",
      "spring.cloud.stream.bindings.approvalEvents-in-0.destination=platform.approval.v1"
    })
@Import(TestJwtConfiguration.class)
class SynonymApprovalIT extends KafkaContainerSupport {

  @Container
  static final GenericContainer<?> ES =
      new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.18.0")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withCommand(
              "sh",
              "-c",
              "bin/elasticsearch-plugin list | grep -q analysis-nori"
                  + " || bin/elasticsearch-plugin install --batch analysis-nori;"
                  + " exec /usr/local/bin/docker-entrypoint.sh eswrapper")
          .withExposedPorts(9200)
          .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(5)));

  @DynamicPropertySource
  static void elasticsearch(DynamicPropertyRegistry r) {
    r.add("pmart.search.elasticsearch-url", SynonymApprovalIT::esUrl);
    // Stream Kafka 바인더는 @ServiceConnection 을 쓰지 않는다(PostgresKafkaContainerSupport 와 같은 까닭)
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  static String esUrl() {
    return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200);
  }

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String PRICE = TestJwt.operator("price-1", List.of("PRICE_APPROVER"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;
  RestClient es;
  KafkaConsumer<String, String> receipts;
  final List<JsonNode> received = new ArrayList<>();

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    es =
        RestClient.builder()
            .baseUrl(esUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    Properties props = new Properties();
    props.put("bootstrap.servers", KAFKA.getBootstrapServers());
    props.put("group.id", "it-receipts-" + UUID.randomUUID());
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    receipts = new KafkaConsumer<>(props);
    receipts.subscribe(List.of("platform.approval-receipt.v1"));
  }

  @AfterEach
  void close() {
    receipts.close();
  }

  @Test
  void approvedChangesApplyOnceByVersionAndReply() {
    // 처음: 버전 0, 규칙 없음. MD 가 아니면 403
    assertThat(get("/search/synonym-rules", PRICE).status()).isEqualTo(403);
    JsonNode initial = get("/search/synonym-rules", MD).body();
    assertThat(initial.path("version").asLong()).isZero();
    assertThat(initial.path("rules")).isEmpty();
    // 상품 색인이 있어야 분석기를 볼 수 있다(첫 검색이 색인을 만든다)
    assertThat(get("/search/products?q=x", MD).status()).isEqualTo(200);

    // ① 승인(기준 버전 0) → 반영, 버전 1, 분석기가 달걀 → 계란 도 낸다(재색인 없음)
    String first = "APR-" + UUID.randomUUID();
    List<Map<String, Object>> eggLines =
        List.of(
            line("egg", "UPSERT", List.of("계란", "달걀")),
            line("onion", "UPSERT", List.of("양파", "Onion")));
    String firstJson = decided(first, "APPROVED", 0, eggLines);
    kafka.send("platform.approval.v1", first, firstJson).join();
    JsonNode r1 = awaitReceipt(first);
    assertThat(r1.at("/payload/result").asString()).isEqualTo("APPLIED");
    assertThat(r1.at("/payload/receiverService").asString()).isEqualTo("search-service");
    assertThat(r1.at("/payload/domainRef").asString()).isEqualTo("pmart-product-synonyms@v1");
    assertThat(r1.at("/payload/lines/0/lineKey").asString()).isEqualTo("egg");
    assertThat(r1.at("/payload/lines/0/result").asString()).isEqualTo("APPLIED");

    JsonNode state = get("/search/synonym-rules", MD).body();
    assertThat(state.path("version").asLong()).isEqualTo(1);
    assertThat(state.path("lastApprovalId").asString()).isEqualTo(first);
    assertThat(state.path("rules").toString())
        .isEqualTo(
            "[{\"ruleId\":\"egg\",\"terms\":[\"계란\",\"달걀\"]},{\"ruleId\":\"onion\",\"terms\":[\"양파\",\"onion\"]}]");
    assertThat(analyze("달걀")).contains("계란");

    // ② 같은 사건 다시(재전달) → 바꾸지 않고 같은 회신만 다시
    kafka.send("platform.approval.v1", first, firstJson).join();
    JsonNode again = awaitReceipt(first);
    assertThat(again.at("/payload/result").asString()).isEqualTo("APPLIED");
    assertThat(again.at("/payload/domainRef").asString()).isEqualTo("pmart-product-synonyms@v1");
    assertThat(get("/search/synonym-rules", MD).body().path("version").asLong()).isEqualTo(1);

    // ③ 옛 기준 버전(0)으로 승인된 다른 요청 → 반영 안 함(STALE_VERSION)
    String stale = "APR-" + UUID.randomUUID();
    kafka
        .send(
            "platform.approval.v1",
            stale,
            decided(stale, "APPROVED", 0, List.of(line("cola", "UPSERT", List.of("콜라", "coke")))))
        .join();
    JsonNode r3 = awaitReceipt(stale);
    assertThat(r3.at("/payload/result").asString()).isEqualTo("REJECTED");
    assertThat(r3.at("/payload/reason").asString()).isEqualTo("STALE_VERSION");
    assertThat(r3.at("/payload/lines/0/reasonCode").asString()).isEqualTo("STALE_VERSION");

    // ④ 반려된 결정은 반영 요청이 아니다 — 회신 없음, 규칙 그대로
    String rejected = "APR-" + UUID.randomUUID();
    kafka
        .send(
            "platform.approval.v1",
            rejected,
            decided(
                rejected, "REJECTED", 1, List.of(line("cola", "UPSERT", List.of("콜라", "coke")))))
        .join();

    // ⑤ 지금 버전(1)으로 지우기·없는 규칙 지우기 → 행 하나가 어긋나 전부 반영 안 함
    String bad = "APR-" + UUID.randomUUID();
    kafka
        .send(
            "platform.approval.v1",
            bad,
            decided(
                bad,
                "APPROVED",
                1,
                List.of(line("egg", "DELETE", null), line("ghost", "DELETE", null))))
        .join();
    JsonNode r5 = awaitReceipt(bad);
    assertThat(r5.at("/payload/result").asString()).isEqualTo("REJECTED");
    assertThat(r5.at("/payload/reason").asString()).isEqualTo("LINE_REJECTED");
    assertThat(r5.at("/payload/lines/1/reasonCode").asString()).isEqualTo("RULE_NOT_FOUND");
    assertThat(get("/search/synonym-rules", MD).body().path("version").asLong()).isEqualTo(1);

    // ⑥ 지금 버전(1)으로 egg 지우기 → 버전 2, 달걀은 더 이상 계란이 아니다
    String del = "APR-" + UUID.randomUUID();
    kafka
        .send(
            "platform.approval.v1",
            del,
            decided(del, "APPROVED", 1, List.of(line("egg", "DELETE", null))))
        .join();
    assertThat(awaitReceipt(del).at("/payload/result").asString()).isEqualTo("APPLIED");
    assertThat(get("/search/synonym-rules", MD).body().path("version").asLong()).isEqualTo(2);
    assertThat(analyze("달걀")).doesNotContain("계란");

    assertThat(received.stream().map(r -> r.at("/payload/approvalId").asString()))
        .as("반려된 결정에는 회신하지 않는다")
        .doesNotContain(rejected);

    // 반영 기록(최근 순) — 색인 새로 고침 뒤
    es.post().uri("/search-synonym-rules/_refresh").retrieve().toBodilessEntity();
    JsonNode apps = get("/search/synonym-rule-applications?size=10", MD).body().path("items");
    List<String> ids = new ArrayList<>();
    apps.forEach(a -> ids.add(a.path("approvalId").asString()));
    assertThat(ids).containsExactly(del, bad, stale, first);
    assertThat(apps.path(1).path("lineReasons").path("ghost").asString())
        .isEqualTo("RULE_NOT_FOUND");
    assertThat(apps.path(3).path("result").asString()).isEqualTo("APPLIED");
    assertThat(apps.path(3).path("version").asLong()).isEqualTo(1);
  }

  private static Map<String, Object> line(String ruleId, String op, List<String> terms) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("op", op);
    if (terms != null) {
      attrs.put("terms", terms);
    }
    return Map.of("lineId", ruleId, "decision", "APPROVED", "attributes", attrs);
  }

  private String decided(
      String approvalId, String decision, int expectedVersion, List<Map<String, Object>> lines) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + approvalId);
    payload.put("type", "SEARCH_SYNONYM");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType", "SynonymSet",
            "targetId", "pmart-product-synonyms",
            "targetService", "search-service",
            "expectedVersion", expectedVersion));
    payload.put("lines", lines);
    payload.put("approverId", "admin-1");
    payload.put("dossierVersion", 2);
    return EventEnvelopeFixtures.envelope("ApprovalDecided")
        .from("approval-service")
        .aggregate("Approval", approvalId, 2)
        .payload(payload)
        .buildJson();
  }

  /** 그 승인의 다음 회신(앞서 받은 것은 건너뜀). */
  private JsonNode awaitReceipt(String approvalId) {
    long seen =
        received.stream()
            .filter(r -> approvalId.equals(r.at("/payload/approvalId").asString()))
            .count();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      for (var rec : receipts.poll(Duration.ofMillis(500))) {
        received.add(mapper.readTree(rec.value()));
        assertThat(rec.key())
            .isEqualTo(mapper.readTree(rec.value()).at("/payload/approvalId").asString());
      }
      List<JsonNode> mine =
          received.stream()
              .filter(r -> approvalId.equals(r.at("/payload/approvalId").asString()))
              .toList();
      if (mine.size() > seen) {
        return mine.get(mine.size() - 1);
      }
    }
    throw new AssertionError("회신이 오지 않음: " + approvalId);
  }

  private List<String> analyze(String text) {
    String raw =
        es.post()
            .uri("/products/_analyze")
            .body(Map.of("analyzer", "name_nori_search", "text", text))
            .retrieve()
            .body(String.class);
    List<String> tokens = new ArrayList<>();
    mapper.readTree(raw).path("tokens").forEach(t -> tokens.add(t.path("token").asString()));
    return tokens;
  }

  private record Res(int status, JsonNode body) {}

  private Res get(String path, String token) {
    var entity =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        entity.getStatusCode().value(),
        mapper.readTree(entity.getBody() == null ? "{}" : entity.getBody()));
  }
}
