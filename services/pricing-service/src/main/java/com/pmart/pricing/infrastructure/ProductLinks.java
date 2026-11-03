package com.pmart.pricing.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.pricing.application.PricingProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product-service 원천 키 조회({@code POST /api/v1/internal/source-link-lookups}). 발급 전·모르는 키는 결과에 없다.
 * 실패는 {@link Downstream} 규칙(미전달·503 → DEPENDENCY_UNAVAILABLE, 시간 초과 → UNKNOWN_RESULT). 조회라 결과 불명도
 * 다시 부르면 된다.
 */
@Component
public class ProductLinks {

  /** 원천 키 → 발급된 상품·SKU(1차는 상품당 SKU 1개라 첫 SKU). */
  public record Link(String productId, String sku) {}

  static final int CHUNK = 2_000;

  private final RestClient product;
  private final ObjectMapper mapper;

  public ProductLinks(RestClient.Builder builder, PricingProperties props, ObjectMapper mapper) {
    this.product = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.mapper = mapper;
  }

  public Map<String, Link> lookup(String sourceSystem, List<String> sourceKeys) {
    Map<String, Link> out = new HashMap<>();
    List<String> keys = sourceKeys.stream().distinct().toList();
    for (int i = 0; i < keys.size(); i += CHUNK) {
      List<String> chunk = new ArrayList<>(keys.subList(i, Math.min(keys.size(), i + CHUNK)));
      String raw =
          Downstream.call(
              "product-service source-link-lookups",
              () ->
                  product
                      .post()
                      .uri("/api/v1/internal/source-link-lookups")
                      .header("X-Source-System", "pricing-service")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("sourceSystem", sourceSystem, "sourceKeys", chunk))
                      .retrieve()
                      .body(String.class));
      JsonNode body = mapper.readTree(raw == null ? "{}" : raw);
      for (JsonNode item : body.path("items")) {
        JsonNode skus = item.path("skus");
        if (skus.isArray() && !skus.isEmpty()) {
          out.put(
              item.path("sourceKey").asString(),
              new Link(item.path("productId").asString(), skus.get(0).asString()));
        }
      }
    }
    return out;
  }
}
