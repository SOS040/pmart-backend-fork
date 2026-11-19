package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Elasticsearch REST 어댑터(클라이언트 라이브러리 없이 RestClient, ADR-0031). 상품·가격·취급 색인은 <b>조회만</b> 한다 — 색인은
 * Logstash 가 도메인 이벤트로 한다({@code src/main/logstash/search-index.conf}). 최근 검색어 {@code
 * search-recent}·검색 기록 {@code search-log}(인기 키워드 집계)는 이 서비스가 쓰고 지운다(2026-11-17). 상품 색인 {@code
 * products}(별칭 → {@code products-v5}, nori·edge·n-gram·공백 제거 n-gram·초성(앞부분) 다중 필드 + 판매 상태 행 버전·승인
 * 속성 필터, 2026-11-05), SKU 가격 {@code sku-prices}(→ {@code sku-prices-v1}).
 *
 * <p>색인이 없으면 같은 정의 파일({@code elasticsearch/*.json})로 만든다(로컬·시험용 — LAN 은 es-init 이 먼저 만든다). 공용
 * RestClient.Builder 는 사용자 토큰을 전파하므로 쓰지 않는다(검색엔진에 고객 JWT 를 보내지 않음).
 */
@Component
public class SearchIndex {

  public static final String PRODUCTS = "products";
  public static final String SKU_PRICES = "sku-prices";

  /** SKU 별 센터 취급(SkuHandlingChanged, 2026-11-05) — Logstash 만 쓰고 읽는다(상품 문서 carriedMfcIds 의 원천). */
  public static final String SKU_HANDLINGS = "sku-handlings";

  /** 고객 최근 검색어(2026-11-17) — 검색자 해시 + 검색어 하나당 문서 하나. 이 서비스가 쓴다. */
  public static final String RECENT_SEARCHES = "search-recent";

  /** 인기 키워드 집계용 검색 기록(2026-11-17) — 센 검색 하나당 문서 하나(검색어·검색자 해시·시각·결과 수). 이 서비스가 쓴다. */
  public static final String SEARCH_LOG = "search-log";

  /** 별칭 → 실제 색인(정의 파일 이름). 매핑을 바꾸면 새 버전 파일을 만들고 여기를 바꾼다. */
  public static final Map<String, String> INDICES =
      Map.of(
          PRODUCTS,
          "products-v5",
          SKU_PRICES,
          "sku-prices-v1",
          SKU_HANDLINGS,
          "sku-handlings-v1",
          RECENT_SEARCHES,
          "search-recent-v1",
          SEARCH_LOG,
          "search-log-v1");

  private static final String TARGET = "elasticsearch";
  private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

  private final RestClient http;
  private final ObjectMapper mapper;

  /**
   * ES 호출 관측(2026-11-19, 성능 분석 2순위 ④): 호출마다 {@code pmart.search.es} 관측 — 지표(타이머, 태그 op·alias·error,
   * 버킷은 pmart-config)와 추적의 자식 span. 이 클라이언트는 공용 Builder 가 아니라 자동 관측이 붙지 않아서 직접 감싼다. 검색어·문서 내용은 태그에
   * 넣지 않는다.
   */
  private final ObservationRegistry observations;

  /** 확인을 마친 별칭(2026-11-19 1순위 ③ — 색인마다 따로, 검색과 무관한 색인 문제가 상품 검색을 막지 않게). */
  private final java.util.Set<String> ready = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private final Map<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

  public SearchIndex(
      SearchProperties props,
      ObjectMapper mapper,
      ObjectProvider<ObservationRegistry> observations) {
    this.observations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.http =
        RestClient.builder()
            .baseUrl(props.elasticsearchUrl())
            .requestFactory(factory)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    this.mapper = mapper;
  }

  /**
   * 서비스가 뜨면 모든 색인을 뒤에서 한 번 확인한다(2026-11-19 1순위 ③ — 첫 검색이 확인 10번을 기다리지 않게). 실패는 경고만 — 그 색인을 처음 쓸 때
   * 다시 확인한다.
   */
  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void warmUp() {
    Thread.ofVirtual()
        .name("search-index-warmup")
        .start(
            () -> {
              for (String alias : INDICES.keySet()) {
                try {
                  ensure(alias);
                } catch (RuntimeException e) {
                  log.warn("Elasticsearch 색인 확인 실패 {} — 처음 쓸 때 다시: {}", alias, e.toString());
                }
              }
            });
  }

  /** 모든 색인·별칭(시험·로컬에서 Logstash 를 띄우기 전에). */
  public void ensureIndices() {
    for (String alias : INDICES.keySet()) {
      ensure(alias);
    }
  }

  /**
   * 그 별칭의 색인·별칭이 없으면 만든다(처음 쓸 때 한 번). 같은 별칭은 동시에 하나만 확인한다(다른 요청은 기다렸다가 결과를 쓴다). 실패하면 다음 요청이 다시
   * 확인한다. 동시에 만들면 한쪽은 이미 있음(400)을 받는다.
   */
  public void ensure(String alias) {
    if (ready.contains(alias)) {
      return;
    }
    String index = INDICES.get(alias);
    if (index == null) {
      throw new IllegalArgumentException("모르는 별칭 " + alias);
    }
    synchronized (locks.computeIfAbsent(alias, k -> new Object())) {
      if (ready.contains(alias)) {
        return;
      }
      int status =
          call(
              "ensure",
              alias,
              () ->
                  http.head()
                      .uri("/{index}", index)
                      .retrieve()
                      .onStatus(HttpStatusCode::is4xxClientError, (q, r) -> {})
                      .toBodilessEntity()
                      .getStatusCode()
                      .value());
      if (status == 404) {
        String body = definition(index);
        try {
          run(
              "ensure",
              alias,
              () -> http.put().uri("/{index}", index).body(body).retrieve().toBodilessEntity());
          log.info("Elasticsearch 색인 생성 {} (별칭 {})", index, alias);
        } catch (HttpClientErrorException.BadRequest already) {
          log.info("Elasticsearch 색인 {} 는 다른 곳에서 먼저 만들었다", index);
        }
      }
      ensureAlias(alias, index);
      ready.add(alias);
    }
  }

  /**
   * 별칭이 아직 없을 때만 붙인다(새 환경). 다른 색인(이전 버전)을 가리키고 있으면 그대로 둔다 — 그 전환은 재색인 절차가 새 색인을 채운 뒤 원자적으로
   * 한다(deploy/lan/elastic/REINDEX.md). 정의 파일에 별칭을 넣지 않는 이유: 이전 색인과 둘 다 별칭을 가지면 쓰기가 실패한다.
   */
  void ensureAlias(String alias, String index) {
    int status =
        call(
            "ensure",
            alias,
            () ->
                http.head()
                    .uri("/_alias/{alias}", alias)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (q, r) -> {})
                    .toBodilessEntity()
                    .getStatusCode()
                    .value());
    if (status == 404) {
      String body =
          "{\"actions\":[{\"add\":{\"index\":\"" + index + "\",\"alias\":\"" + alias + "\"}}]}";
      try {
        run(
            "ensure",
            alias,
            () -> http.post().uri("/_aliases").body(body).retrieve().toBodilessEntity());
        log.info("Elasticsearch 별칭 {} → {}", alias, index);
      } catch (HttpClientErrorException.BadRequest race) {
        log.info("Elasticsearch 별칭 {} 는 다른 곳에서 먼저 붙였다", alias);
      }
    }
  }

  public JsonNode search(String alias, Map<String, Object> request) {
    ensure(alias);
    String raw =
        call(
            "search",
            alias,
            () ->
                http.post()
                    .uri("/{alias}/_search", alias)
                    .body(mapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }

  /** 문서를 id 로 넣거나 덮어쓴다(별칭으로 — 별칭은 색인 하나만 가리킨다). 새로 고침을 기다리지 않는다 — 다음 조회에는 색인 새로 고침(기본 1초) 뒤 보인다. */
  public void put(String alias, String id, Map<String, Object> document) {
    ensure(alias);
    run(
        "put",
        alias,
        () ->
            http.put()
                .uri("/{alias}/_doc/{id}", alias, id)
                .body(mapper.writeValueAsString(document))
                .retrieve()
                .toBodilessEntity());
  }

  /**
   * 외부 버전(예 시각 밀리초)이 같거나 클 때만 넣거나 덮어쓴다 — 늦게 도착한 옛 쓰기가 새 쓰기를 덮지 않는다. 더 새 버전이 이미 있으면(409) 아무 것도 하지 않고
   * false.
   */
  public boolean putIfNewer(String alias, String id, long version, Map<String, Object> document) {
    ensure(alias);
    int status =
        call(
            "put",
            alias,
            () ->
                http.put()
                    .uri(
                        "/{alias}/_doc/{id}?version={v}&version_type=external_gte",
                        alias,
                        id,
                        version)
                    .body(mapper.writeValueAsString(document))
                    .retrieve()
                    .onStatus(s -> s.value() == 409, (q, r) -> {})
                    .toBodilessEntity()
                    .getStatusCode()
                    .value());
    return status != 409;
  }

  /** 문서 하나를 지운다. 없으면(404) 그대로 성공(멱등). */
  public void delete(String alias, String id) {
    ensure(alias);
    run(
        "delete",
        alias,
        () ->
            http.delete()
                .uri("/{alias}/_doc/{id}?refresh=wait_for", alias, id)
                .retrieve()
                .onStatus(s -> s.value() == 404, (q, r) -> {})
                .toBodilessEntity());
  }

  /** 조건에 맞는 문서를 지운다(버전 충돌은 건너뜀 — 다시 돌리면 된다). 지운 건수. */
  public long deleteByQuery(String alias, Map<String, Object> query) {
    ensure(alias);
    String raw =
        call(
            "delete_by_query",
            alias,
            () ->
                http.post()
                    .uri("/{alias}/_delete_by_query?conflicts=proceed&refresh=true", alias)
                    .body(mapper.writeValueAsString(Map.of("query", query)))
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw).path("deleted").asLong(0);
  }

  private <T> T call(String op, String alias, java.util.function.Supplier<T> request) {
    return Observation.createNotStarted("pmart.search.es", observations)
        .contextualName("es " + op)
        .lowCardinalityKeyValue("op", op)
        .lowCardinalityKeyValue("alias", alias)
        .observe(() -> Downstream.call(TARGET, request));
  }

  private void run(String op, String alias, Runnable request) {
    Observation.createNotStarted("pmart.search.es", observations)
        .contextualName("es " + op)
        .lowCardinalityKeyValue("op", op)
        .lowCardinalityKeyValue("alias", alias)
        .observe(() -> Downstream.run(TARGET, request));
  }

  /** 색인 정의(설정·매핑·별칭) JSON. es-init 도 같은 파일을 쓴다. */
  public static String definition(String index) {
    try (InputStream in =
        new ClassPathResource("elasticsearch/" + index + ".json").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("색인 정의 없음: " + index, ex);
    }
  }
}
