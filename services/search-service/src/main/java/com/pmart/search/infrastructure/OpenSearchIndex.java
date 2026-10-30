package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * OpenSearch REST 어댑터(클라이언트 라이브러리 없이 RestClient). 상품 색인 {@code products}, SKU 가격 {@code
 * sku-prices}(둘 다 별칭 → 실제 인덱스 {@code -v1}).
 *
 * <p>공용 RestClient.Builder 는 사용자 토큰을 전파하므로 쓰지 않는다(검색엔진에 고객 JWT 를 보내지 않음). 쓰기는 {@code
 * refresh=wait_for} — 색인 직후 조회와 가격 반영(update_by_query)이 방금 쓴 문서를 보도록.
 */
@Component
public class OpenSearchIndex {

  public static final String PRODUCTS = "products";
  public static final String SKU_PRICES = "sku-prices";
  private static final Map<String, String> INDICES =
      Map.of(PRODUCTS, "products-v1", SKU_PRICES, "sku-prices-v1");
  private static final String TARGET = "opensearch";
  private static final Logger log = LoggerFactory.getLogger(OpenSearchIndex.class);

  private final RestClient http;
  private final ObjectMapper mapper;
  private final AtomicBoolean ready = new AtomicBoolean();

  public OpenSearchIndex(SearchProperties props, ObjectMapper mapper) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.http =
        RestClient.builder()
            .baseUrl(props.opensearchUrl())
            .requestFactory(factory)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    this.mapper = mapper;
  }

  /** 인덱스·별칭이 없으면 만든다(처음 쓰거나 읽을 때 한 번). 동시에 만들면 한쪽은 이미 있음(400)을 받는다. */
  public void ensureIndices() {
    if (ready.get()) {
      return;
    }
    for (Map.Entry<String, String> e : INDICES.entrySet()) {
      String index = e.getValue();
      int status =
          Downstream.call(
              TARGET,
              () ->
                  http.head()
                      .uri("/{index}", index)
                      .retrieve()
                      .onStatus(HttpStatusCode::is4xxClientError, (q, r) -> {})
                      .toBodilessEntity()
                      .getStatusCode()
                      .value());
      if (status == 404) {
        String body = resource("opensearch/" + index + ".json");
        try {
          Downstream.run(
              TARGET,
              () -> http.put().uri("/{index}", index).body(body).retrieve().toBodilessEntity());
          log.info("OpenSearch 인덱스 생성 {} (별칭 {})", index, e.getKey());
        } catch (HttpClientErrorException.BadRequest already) {
          log.info("OpenSearch 인덱스 {} 는 다른 인스턴스가 먼저 만들었다", index);
        }
      }
    }
    ready.set(true);
  }

  /** 문서 하나를 스크립트로 갱신(없으면 빈 문서에서 시작 — scripted_upsert). 스크립트가 버전을 비교해 멱등. */
  public void scriptedUpsert(String alias, String id, String script, Map<String, Object> params) {
    ensureIndices();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("scripted_upsert", true);
    body.put("script", Map.of("lang", "painless", "source", script, "params", params));
    body.put("upsert", Map.of());
    String json = mapper.writeValueAsString(body);
    Downstream.run(
        TARGET,
        () ->
            http.post()
                .uri(
                    "/{alias}/_update/{id}?refresh=wait_for&retry_on_conflict=3",
                    INDICES.get(alias),
                    id)
                .body(json)
                .retrieve()
                .toBodilessEntity());
  }

  /** 조건에 맞는 문서 모두에 스크립트 적용(가격 → 그 SKU 를 대표로 둔 상품). */
  public void updateByQuery(
      String alias, Map<String, Object> query, String script, Map<String, Object> params) {
    ensureIndices();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("script", Map.of("lang", "painless", "source", script, "params", params));
    String json = mapper.writeValueAsString(body);
    Downstream.run(
        TARGET,
        () ->
            http.post()
                .uri("/{alias}/_update_by_query?refresh=true&conflicts=proceed", alias)
                .body(json)
                .retrieve()
                .toBodilessEntity());
  }

  public Optional<JsonNode> get(String alias, String id) {
    ensureIndices();
    String raw =
        Downstream.call(
            TARGET,
            () ->
                http.get()
                    .uri("/{alias}/_doc/{id}", alias, id)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (q, r) -> {})
                    .body(String.class));
    JsonNode n = raw == null ? null : mapper.readTree(raw);
    return n != null && n.path("found").asBoolean(false)
        ? Optional.of(n.path("_source"))
        : Optional.empty();
  }

  public JsonNode search(String alias, Map<String, Object> request) {
    ensureIndices();
    String json = mapper.writeValueAsString(request);
    String raw =
        Downstream.call(
            TARGET,
            () ->
                http.post()
                    .uri("/{alias}/_search", alias)
                    .body(json)
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }

  private static String resource(String path) {
    try (InputStream in = new ClassPathResource(path).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("인덱스 정의를 읽지 못했다: " + path, e);
    }
  }
}
