package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
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
 * Elasticsearch REST 어댑터(클라이언트 라이브러리 없이 RestClient, ADR-0031). 이 서비스는 <b>조회만</b> 한다 — 색인은 Logstash
 * 가 도메인 이벤트로 한다({@code src/main/logstash/search-index.conf}). 상품 색인 {@code products}(별칭 → {@code
 * products-v2}, nori·edge·n-gram 다중 필드), SKU 가격 {@code sku-prices}(→ {@code sku-prices-v1}).
 *
 * <p>색인이 없으면 같은 정의 파일({@code elasticsearch/*.json})로 만든다(로컬·시험용 — LAN 은 es-init 이 먼저 만든다). 공용
 * RestClient.Builder 는 사용자 토큰을 전파하므로 쓰지 않는다(검색엔진에 고객 JWT 를 보내지 않음).
 */
@Component
public class SearchIndex {

  public static final String PRODUCTS = "products";
  public static final String SKU_PRICES = "sku-prices";

  /** 별칭 → 실제 색인(정의 파일 이름). 매핑을 바꾸면 새 버전 파일을 만들고 여기를 바꾼다. */
  public static final Map<String, String> INDICES =
      Map.of(PRODUCTS, "products-v2", SKU_PRICES, "sku-prices-v1");

  private static final String TARGET = "elasticsearch";
  private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

  private final RestClient http;
  private final ObjectMapper mapper;
  private final AtomicBoolean ready = new AtomicBoolean();

  public SearchIndex(SearchProperties props, ObjectMapper mapper) {
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

  /** 색인·별칭이 없으면 만든다(처음 읽을 때 한 번). 동시에 만들면 한쪽은 이미 있음(400)을 받는다. */
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
        String body = definition(index);
        try {
          Downstream.run(
              TARGET,
              () -> http.put().uri("/{index}", index).body(body).retrieve().toBodilessEntity());
          log.info("Elasticsearch 색인 생성 {} (별칭 {})", index, e.getKey());
        } catch (HttpClientErrorException.BadRequest already) {
          log.info("Elasticsearch 색인 {} 는 다른 곳에서 먼저 만들었다", index);
        }
      }
    }
    ready.set(true);
  }

  public JsonNode search(String alias, Map<String, Object> request) {
    ensureIndices();
    String raw =
        Downstream.call(
            TARGET,
            () ->
                http.post()
                    .uri("/{alias}/_search", alias)
                    .body(mapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
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
