package com.pmart.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.KafkaContainerSupport;
import com.pmart.common.test.TestJwtConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 의미 검색(ADR-0088) — 실제 ES 8.18(products-v11 매핑·kNN 질의 모양)과 가짜 ollama(검색어에 "빨간"이 있으면 첫 축, 아니면 둘째 축
 * 벡터). 뒤 작업(꼬리표 만들기)은 tagModel 을 비워 끄고, 시험 문서에 벡터·꼬리표를 직접 넣는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.search.semantic.enabled=true",
      "pmart.search.semantic.embedding-model=test-embed",
      "pmart.search.semantic.similarity=0.5",
      "pmart.search.semantic.knn-boost=2",
      "pmart.search.semantic.knn-k=10",
      "pmart.search.semantic.query-timeout=PT2S"
    })
@Import(TestJwtConfiguration.class)
class SemanticSearchIT extends KafkaContainerSupport {

  static final int DIMS = 1024;

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

  static final HttpServer OLLAMA = fakeOllama();

  static HttpServer fakeOllama() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(
          "/api/embed",
          ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String v = vectorJson(body.contains("빨간") ? 0 : 1);
            byte[] out = ("{\"embeddings\":[" + v + "]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
          });
      s.start();
      return s;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String vectorJson(int axis) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < DIMS; i++) {
      b.append(i == 0 ? "" : ",").append(i == axis ? "1.0" : "0.0");
    }
    return b.append("]").toString();
  }

  static List<Double> vector(int axis) {
    List<Double> v = new ArrayList<>();
    for (int i = 0; i < DIMS; i++) {
      v.add(i == axis ? 1.0 : 0.0);
    }
    return v;
  }

  @AfterAll
  static void stop() {
    OLLAMA.stop(0);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add(
        "pmart.search.elasticsearch-url",
        () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
    r.add(
        "pmart.search.semantic.ollama-base-url",
        () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  RestClient http;
  RestClient es;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    es =
        RestClient.builder()
            .baseUrl("http://" + ES.getHost() + ":" + ES.getMappedPort(9200))
            .defaultHeader("Content-Type", "application/json")
            .build();
    search("두부"); // 색인을 만들게(서비스가 products-v11 정의로)
    product("PRD-S1", "[시험] 짬뽕 컵라면", null, vector(0));
    product("PRD-S2", "[시험] 사골 곰탕면", null, vector(1));
    product("PRD-S3", "[시험] 불맛 라면", "빨간 매운 국물", vector(1));
    es.post().uri("/products/_refresh").retrieve().toBodilessEntity();
  }

  void product(String id, String name, String tags, List<Double> vector) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("productId", id);
    d.put("productVersion", 1);
    d.put("name", name);
    d.put("saleState", "ON_SALE");
    if (tags != null) {
      d.put("aiTags", tags);
    }
    d.put("semanticVector", vector);
    es.put()
        .uri("/products/_doc/{id}", id)
        .body(mapper.writeValueAsString(d))
        .retrieve()
        .toBodilessEntity();
  }

  JsonNode search(String q) {
    var e = http.get().uri("/search/products?q={q}", q).retrieve().toEntity(String.class);
    assertThat(e.getStatusCode().value()).as(q + " " + e.getBody()).isEqualTo(200);
    return mapper.readTree(e.getBody());
  }

  @Test
  void vectorFindsProductsWhoseNamesDoNotContainTheWords() {
    // "빨간 국물" — 이름에 없는 말. 가짜 벡터가 PRD-S1 쪽 → 벡터로 찾고, S3 는 꼬리표(빨간·국물)로
    List<String> ids = search("빨간 국물").path("items").findValuesAsString("productId");
    assertThat(ids).contains("PRD-S1", "PRD-S3").doesNotContain("PRD-S2");
  }

  @Test
  void keywordMatchStaysFirstAndTagsJoinTheKeywordPath() {
    // "빨간 라면": 꼬리표 빨간 + 이름 라면 → S3 키워드 일치, S1 은 벡터로 뒤에
    List<String> ids = search("빨간 라면").path("items").findValuesAsString("productId");
    assertThat(ids).startsWith("PRD-S3").contains("PRD-S1");
    // 이름으로 맞는 검색어는 지금처럼("곰탕면" → S2 만, 벡터는 둘째 축이라 S2·S3 비슷함 1 이지만 S2 가 이름 일치로 먼저)
    assertThat(search("곰탕면").path("items").findValuesAsString("productId")).startsWith("PRD-S2");
  }
}
