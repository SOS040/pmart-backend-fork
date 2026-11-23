package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import com.pmart.search.domain.ListAvailability;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product-content 공개 카드 일괄 조회({@code GET /product-contents?productIds=&mfcId=}, 계약 security: [], 최대
 * 100건)의 옵션별 센터 가용 사본(계약 0.10.0, 2026-11-23). 검색 응답을 붙잡지 않도록 연결 0.5초·응답 1초 — 늦거나 실패하면 예외(부르는 쪽이
 * UNKNOWN 으로). 주소({@code pmart.search.content-base-url})가 없으면 꺼짐.
 */
@Component
public class ContentAvailabilityClient {

  static final int MAX_IDS = 100;
  static final Duration CONNECT = Duration.ofMillis(500);
  static final Duration READ = Duration.ofSeconds(1);

  private final @Nullable RestClient http;
  private final ObjectMapper mapper;

  public ContentAvailabilityClient(
      RestClient.Builder builder, SearchProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    if (props.contentBaseUrl() == null || props.contentBaseUrl().isBlank()) {
      this.http = null;
      return;
    }
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT).build());
    factory.setReadTimeout(READ);
    this.http = builder.clone().baseUrl(props.contentBaseUrl()).requestFactory(factory).build();
  }

  public boolean enabled() {
    return http != null;
  }

  /** 상품 ID → 옵션 사본. 응답에 없는 상품(미게시)은 맵에 없다. 연결 실패·시간 초과·5xx·4xx 는 예외. */
  public Map<String, List<ListAvailability.Option>> options(List<String> productIds, String mfcId) {
    RestClient client = http;
    if (client == null) {
      throw new IllegalStateException("content-base-url 없음");
    }
    Map<String, List<ListAvailability.Option>> out = new HashMap<>();
    for (int i = 0; i < productIds.size(); i += MAX_IDS) {
      String ids =
          String.join(",", productIds.subList(i, Math.min(productIds.size(), i + MAX_IDS)));
      String raw =
          Downstream.call(
              "product-content-service cards",
              () ->
                  client
                      .get()
                      .uri(
                          u ->
                              u.path("/api/v1/product-contents")
                                  .queryParam("productIds", ids)
                                  .queryParam("mfcId", mfcId)
                                  .build())
                      .retrieve()
                      .body(String.class));
      for (JsonNode c : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        List<ListAvailability.Option> opts = new ArrayList<>();
        for (JsonNode o : c.path("options")) {
          JsonNode a = o.path("availability");
          opts.add(
              new ListAvailability.Option(
                  a.path("carried").isBoolean() ? a.path("carried").asBoolean() : null,
                  a.path("state").isString() ? a.path("state").asString() : null));
        }
        out.put(c.path("productId").asString(), opts);
      }
    }
    return out;
  }
}
