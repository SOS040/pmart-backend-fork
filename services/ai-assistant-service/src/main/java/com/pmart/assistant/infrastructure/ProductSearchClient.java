package com.pmart.assistant.infrastructure;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.domain.ProductAnswerText;
import com.pmart.common.web.Downstream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * search-service 상품 검색({@code GET /search/products}, 공개 작업) — 2026-11-20 사용자 "상품검색 ES 를 RAG 에
 * 연결"(ADR-0083). ES 에 직접 붙지 않고 검색 서비스 API 만 쓴다.
 *
 * <p>토큰을 붙이지 않는다: 익명 검색은 최근 검색어·인기 키워드 기록에 남지 않는다(검색 계약 q 설명). 주소({@code
 * pmart.assistant.product-search.base-url})가 없으면 꺼져 있다 — 상품 질문도 문서 검색으로 답한다.
 */
@Component
public class ProductSearchClient {

  public static final String SEARCH = "search-service";

  /** 검색 결과. total = 조건에 맞는 전체 건수. */
  public record Result(List<ProductAnswerText.Item> items, long total) {}

  private final @Nullable RestClient client;
  private final ObjectMapper json;
  private final int maxItems;

  public ProductSearchClient(
      RestClient.Builder builder, AssistantProperties props, ObjectMapper json) {
    String base = props.getProductSearch().getBaseUrl();
    this.client =
        base == null || base.isBlank()
            ? null
            : builder
                .clone()
                .baseUrl(base)
                // 공용 Builder 는 현재 사용자 토큰을 전파한다(ServiceCallHeadersCustomizer) — 그 뒤에 떼어 익명 검색으로
                .requestInterceptor(
                    (req, body, exec) -> {
                      req.getHeaders().remove(HttpHeaders.AUTHORIZATION);
                      return exec.execute(req, body);
                    })
                .build();
    this.json = json;
    this.maxItems = props.getProductSearch().getMaxItems();
  }

  public boolean enabled() {
    return client != null;
  }

  /**
   * 검색어로 상위 {@code max-items} 개. {@code centerId} 를 주면 그 센터 미취급도 함께 받아 취급 여부를 표시한다. 꺼져 있으면 비어 있다.
   * 연결 실패·5xx 는 {@link Downstream} 예외.
   */
  public Optional<Result> search(String keyword, @Nullable String centerId) {
    if (client == null) {
      return Optional.empty();
    }
    boolean center = centerId != null && !centerId.isBlank();
    String body =
        Downstream.call(
            SEARCH,
            () ->
                client
                    .get()
                    .uri(
                        u -> {
                          u.path("/api/v1/search/products")
                              .queryParam("q", keyword)
                              .queryParam("size", maxItems);
                          if (center) {
                            u.queryParam("mfcId", centerId).queryParam("includeNotCarried", true);
                          }
                          return u.build();
                        })
                    .exchange(
                        (req, res) -> {
                          int s = res.getStatusCode().value();
                          if (s >= 500) {
                            throw new IllegalStateException("search-service " + s);
                          }
                          return s == 200 ? new String(res.getBody().readAllBytes()) : null;
                        }));
    if (body == null) {
      return Optional.empty();
    }
    JsonNode n = json.readTree(body);
    List<ProductAnswerText.Item> items = new ArrayList<>();
    for (JsonNode h : n.path("items")) {
      if (items.size() >= maxItems) {
        break;
      }
      String name = h.path("name").asString("");
      if (name.isBlank()) {
        continue;
      }
      JsonNode price = h.path("basePrice"); // Money = KRW 정수, 가격이 아직 없으면 비어 있다
      Long won = price.isNumber() ? price.asLong() : null;
      JsonNode carried = h.path("carried");
      items.add(
          new ProductAnswerText.Item(
              name,
              won,
              h.path("saleState").asString("UNKNOWN"),
              carried.isBoolean() ? carried.asBoolean() : null));
    }
    long total = n.path("total").isNumber() ? n.path("total").asLong() : items.size();
    return Optional.of(new Result(List.copyOf(items), total));
  }
}
