package com.pmart.product.content.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 가용 조회({@code /internal/availability}, 서비스 토큰 — 공용 RestClient 가 {@code /internal/} 에
 * 붙인다). 상품 화면 표시용 사본이다. 실패는 예외 — 호출자가 불명으로 둔다(0·품절로 바꾸지 않는다).
 */
@Component
public class InventoryClient {

  /** 계약 상한(요청당 SKU 100개). */
  static final int MAX_SKUS = 100;

  public record Stock(@Nullable Integer available, boolean handlingEnabled) {}

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder,
      @Value("${pmart.content.inventory-base-url:http://mfc-inventory-service:8301}")
          String baseUrl,
      ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 응답에 없는 SKU 는 빠진다. */
  public Map<String, Stock> stock(String mfcId, List<String> skus) {
    Map<String, Stock> out = new HashMap<>();
    List<String> distinct = skus.stream().distinct().toList();
    for (int from = 0; from < distinct.size(); from += MAX_SKUS) {
      List<String> chunk =
          new ArrayList<>(distinct.subList(from, Math.min(from + MAX_SKUS, distinct.size())));
      String raw =
          Downstream.call(
              "mfc-inventory-service availability",
              () ->
                  inventory
                      .get()
                      .uri(
                          u -> {
                            u.path("/api/v1/internal/availability").queryParam("mfcId", mfcId);
                            chunk.forEach(s -> u.queryParam("sku", s));
                            return u.build();
                          })
                      .retrieve()
                      .body(String.class));
      for (JsonNode a : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        JsonNode available = a.path("available");
        out.put(
            a.path("sku").asString(),
            new Stock(
                available.isNumber() ? available.asInt() : null,
                a.path("handlingEnabled").asBoolean(false)));
      }
    }
    return out;
  }
}
