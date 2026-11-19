package com.pmart.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.KafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.search.application.PopularKeywords;
import com.pmart.search.domain.SearchKeywords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
 * 최근 검색어·인기 키워드(사용자 결정 2026-11-17) — 실제 Elasticsearch(nori). 상품 문서는 시험이 색인에 바로 넣는다(Logstash 경로는
 * SearchFlowIT). 기록은 비동기라 결과가 보일 때까지 기다린다. 설정은 pmart-config 사본(최근 10개·90일, 인기 7일·10개·2명 이상)에 숨김 목록만
 * 덮는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.search.popular.hidden-keywords[0]=서울"
    })
@Import(TestJwtConfiguration.class)
class SearchActivityIT extends KafkaContainerSupport {

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
    r.add("pmart.search.elasticsearch-url", SearchActivityIT::esUrl);
  }

  static String esUrl() {
    return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired PopularKeywords popular;

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
            .baseUrl(esUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    // 색인을 만들게 한 번 부르고(서비스가 정의 파일로 만든다), 시험 상품을 넣는다
    search(null, "");
    product("PRD-A1", "[시험] 국산콩 두부 300g");
    product("PRD-A2", "[시험] 서울 우유 1L");
    es.post().uri("/products/_refresh").retrieve().toBodilessEntity();
  }

  @Test
  void customerSearchIsRecordedNewestFirstAndSameKeywordMovesToTop() throws Exception {
    String token = TestJwt.customer("cust-" + UUID.randomUUID());
    search(token, "  두부   300g ");
    pause();
    search(token, "우유");
    pause();
    search(token, "두부 300g"); // 같은 검색어 → 하나만, 맨 위로
    // 다음 페이지(cursor)는 기록하지 않는다
    http.get()
        .uri("/search/products?q={q}&cursor={c}", "커서검색", "bzox")
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toBodilessEntity();

    List<String> keywords = awaitRecent(token, k -> k.size() == 2 && k.get(0).equals("두부 300g"));
    assertThat(keywords).containsExactly("두부 300g", "우유");
    JsonNode body = recent(token);
    assertThat(body.path("items").get(0).path("searchedAt").asString()).isNotBlank();
  }

  @Test
  void keepsOnlyLatestTen() throws Exception {
    String subject = "cust-" + UUID.randomUUID();
    String token = TestJwt.customer(subject);
    for (int i = 1; i <= 12; i++) {
      search(token, "열개시험" + i);
      pause();
    }
    List<String> keywords = awaitRecent(token, k -> k.size() == 10 && k.get(0).equals("열개시험12"));
    assertThat(keywords).hasSize(10).first().isEqualTo("열개시험12");
    assertThat(keywords).doesNotContain("열개시험1", "열개시험2");
    // 색인에도 10개만 남는다(쓸 때 정리). 정리는 새로 고침(1초)된 문서만 보므로 20ms 간격으로 몰아 쓰면 덜 지워질 수 있고, 설계대로 다음 기록 때
    // 정리된다(RecentSearches.trim) — 2026-11-19 시험이 이 타이밍에 기대 실패하던 것을 고침: 새로 고친 뒤 한 번 더 기록하고 본다
    String hash = SearchKeywords.searcherHash("it-searcher-hash-key", "CUSTOMER", subject);
    countRecentDocs(hash); // 새로 고침
    search(token, "열개시험12");
    await(() -> countRecentDocs(hash) == 10, () -> "색인의 최근 검색어 " + countRecentDocs(hash));
  }

  @Test
  void deleteOneAndAllAreIdempotent() throws Exception {
    String token = TestJwt.customer("cust-" + UUID.randomUUID());
    search(token, "삭제 시험");
    pause();
    search(token, "남길말");
    awaitRecent(token, k -> k.size() == 2);

    assertThat(delete(token, "/me/recent-searches/{k}", "삭제  시험")).isEqualTo(204);
    assertThat(keywords(recent(token))).containsExactly("남길말");
    assertThat(delete(token, "/me/recent-searches/{k}", "삭제 시험")).isEqualTo(204);

    assertThat(delete(token, "/me/recent-searches", null)).isEqualTo(204);
    assertThat(keywords(recent(token))).isEmpty();
    assertThat(delete(token, "/me/recent-searches", null)).isEqualTo(204);
  }

  @Test
  void onlyCustomersHaveRecentSearches() {
    assertThat(status(TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of())))
        .isEqualTo(403);
    assertThat(status(guestToken("guest-1"))).isEqualTo(403);
    assertThat(status(null)).isEqualTo(401);
    assertThat(
            delete(
                TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of()),
                "/me/recent-searches",
                null))
        .isEqualTo(403);
  }

  @Test
  void popularCountsDistinctSearchersWithResultsAndSkipsHidden() throws Exception {
    String a = TestJwt.customer("pop-a-" + UUID.randomUUID());
    String b = TestJwt.customer("pop-b-" + UUID.randomUUID());
    String c = TestJwt.customer("pop-c-" + UUID.randomUUID());
    String g = guestToken("pop-g-" + UUID.randomUUID());
    // 국산콩: 고객 a·b·비회원 g → 3명 3번
    search(a, "국산콩");
    search(b, "국산콩");
    search(g, "국산콩");
    // 우유: a 3번 + b 1번 → 2명 4번 (횟수 순으로 국산콩보다 위)
    for (int i = 0; i < 3; i++) {
      search(a, "우유");
    }
    search(b, "우유");
    // 혼자 되풀이: c 5번 → 1명이라 제외
    for (int i = 0; i < 5; i++) {
      search(c, "두부");
    }
    // 결과 0건: a·b → 제외
    search(a, "없는상품zzz");
    search(b, "없는상품zzz");
    // 숨김 목록(서울): a·b → 제외
    search(a, "서울");
    search(b, "서울");
    // 익명 3번 + a 1번 → 1명(익명은 세지 않음)
    for (int i = 0; i < 3; i++) {
      search(null, "1L");
    }
    search(a, "1L");
    // 운영자 검색은 세지 않는다
    String op = TestJwt.operator("op-pop", List.of("OPERATOR_ADMIN"), List.of());
    search(op, "1L");

    await(
        () -> {
          es.post().uri("/search-log/_refresh").retrieve().toBodilessEntity();
          return popular.refreshNow().equals(List.of("우유", "국산콩"));
        },
        () -> "인기 키워드 [우유, 국산콩] — 지금 " + popular.refreshNow());
    assertThat(popular.refreshNow()).containsExactly("우유", "국산콩");

    JsonNode suggestions =
        mapper.readTree(
            http.get().uri("/search/suggestions?q={q}", "두").retrieve().body(String.class));
    assertThat(suggestions.path("popular").toString()).isEqualTo("[\"우유\",\"국산콩\"]");
    // 색인에는 검색자 원래 ID 가 없다(해시만)
    String raw =
        es.post()
            .uri("/search-log/_search")
            .body("{\"size\":100,\"query\":{\"term\":{\"keyword\":\"국산콩\"}}}")
            .retrieve()
            .body(String.class);
    assertThat(raw).doesNotContain("pop-a-").doesNotContain("pop-g-");
  }

  // ---------- 도움 ----------

  static String guestToken(String sessionId) {
    return TestJwt.token(sessionId, "GUEST", List.of(), List.of(), "customer-app");
  }

  void product(String id, String name) {
    es.put()
        .uri("/products/_doc/{id}", id)
        .body(
            mapper.writeValueAsString(
                Map.of("productId", id, "productVersion", 1, "name", name, "saleState", "ON_SALE")))
        .retrieve()
        .toBodilessEntity();
  }

  int search(@Nullable String token, String q) {
    var req = http.get().uri("/search/products?q={q}", q);
    if (token != null) {
      req.header("Authorization", "Bearer " + token);
    }
    int status = req.exchange((r, s) -> s.getStatusCode().value());
    assertThat(status).as("검색 " + q).isEqualTo(200);
    return status;
  }

  JsonNode recent(String token) {
    return mapper.readTree(
        http.get()
            .uri("/me/recent-searches")
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(String.class));
  }

  int status(@Nullable String token) {
    var req = http.get().uri("/me/recent-searches");
    if (token != null) {
      req.header("Authorization", "Bearer " + token);
    }
    return req.exchange((r, s) -> s.getStatusCode().value());
  }

  int delete(String token, String path, @Nullable String keyword) {
    var spec = keyword == null ? http.delete().uri(path) : http.delete().uri(path, keyword);
    return spec.header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("X-Source-System", "customer-app")
        .exchange((r, s) -> s.getStatusCode().value());
  }

  static List<String> keywords(JsonNode body) {
    List<String> out = new ArrayList<>();
    body.path("items").forEach(i -> out.add(i.path("keyword").asString()));
    return out;
  }

  List<String> awaitRecent(String token, java.util.function.Predicate<List<String>> done)
      throws Exception {
    await(() -> done.test(keywords(recent(token))), () -> "최근 검색어 " + keywords(recent(token)));
    return keywords(recent(token));
  }

  long countRecentDocs(String hash) {
    es.post().uri("/search-recent/_refresh").retrieve().toBodilessEntity();
    String raw =
        es.post()
            .uri("/search-recent/_count")
            .body("{\"query\":{\"term\":{\"searcher\":\"" + hash + "\"}}}")
            .retrieve()
            .body(String.class);
    return mapper.readTree(raw).path("count").asLong();
  }

  /** 비동기 기록을 기다린다(최대 20초). */
  static void await(Supplier<Boolean> condition, Supplier<String> message) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < until) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("기다렸지만 안 됨: " + message.get());
  }

  /** 검색 시각(밀리초)이 겹치지 않게. */
  static void pause() throws InterruptedException {
    Thread.sleep(20);
  }

  /** 결과 0건 검색어(ADR-0073, 2026-11-20): 고객 검색 기록에서 0건만 많이 나온 순, MD·OPERATOR_ADMIN 만(그 밖 403). */
  @Test
  void zeroResultKeywordsForOperators() throws Exception {
    String a = TestJwt.customer("cust-" + UUID.randomUUID());
    String b = TestJwt.customer("cust-" + UUID.randomUUID());
    search(a, "없는상품말");
    pause();
    search(b, "없는상품말");
    pause();
    search(a, "두부"); // 결과 있음 — 세지 않는다
    String md = TestJwt.operator("op-md", List.of("MD"), List.of());
    JsonNode[] got = new JsonNode[1];
    await(
        () -> {
          es.post().uri("/search-log/_refresh").retrieve().toBodilessEntity();
          got[0] =
              mapper.readTree(
                  http.get()
                      .uri("/search/zero-result-keywords?days=7")
                      .header("Authorization", "Bearer " + md)
                      .retrieve()
                      .body(String.class));
          for (JsonNode i : got[0].path("items")) {
            if (i.path("keyword").asString().equals("없는상품말") && i.path("searches").asInt() == 2) {
              return true;
            }
          }
          return false;
        },
        () -> "0건 검색어 — 지금 " + got[0]);
    for (JsonNode i : got[0].path("items")) {
      assertThat(i.path("keyword").asString()).isNotEqualTo("두부");
      if (i.path("keyword").asString().equals("없는상품말")) {
        assertThat(i.path("searchers").asInt()).isEqualTo(2);
      }
    }
    String other = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());
    int status =
        http.get()
            .uri("/search/zero-result-keywords?days=7")
            .header("Authorization", "Bearer " + other)
            .exchange((q, r) -> r.getStatusCode().value());
    assertThat(status).isEqualTo(403);
  }
}
