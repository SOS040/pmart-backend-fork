package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product-content-service 공개 카드 조회({@code GET /product-contents?productIds=}, 계약 security: []).
 * 게시본만 온다. 사건 처리 스레드라 사용자 토큰은 없다.
 */
@Component
public class ContentCardClient {

  /** 고객용 상품명·대표 이미지 경로·콘텐츠 버전. */
  public record Card(String name, @Nullable String imageUrl, int contentVersion) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public ContentCardClient(
      RestClient.Builder builder, SearchProperties props, ObjectMapper mapper) {
    this.http = builder.baseUrl(props.contentBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 게시본이 없으면 empty. 연결 실패·5xx 는 예외(사건 재처리). */
  public Optional<Card> card(String productId) {
    String raw =
        Downstream.call(
            "product-content-service cards",
            () ->
                http.get()
                    .uri("/api/v1/product-contents?productIds={id}", productId)
                    .retrieve()
                    .body(String.class));
    JsonNode items = mapper.readTree(raw == null ? "{}" : raw).path("items");
    for (JsonNode c : items) {
      if (productId.equals(c.path("productId").asString()) && c.hasNonNull("name")) {
        JsonNode img = c.path("mainImage").path("url");
        return Optional.of(
            new Card(
                c.get("name").asString(),
                img.isString() ? img.asString() : null,
                c.path("contentVersion").asInt(0)));
      }
    }
    return Optional.empty();
  }
}
