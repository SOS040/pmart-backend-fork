package com.pmart.product.content.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * pricing 기본가 조회({@code GET /api/v1/prices?skus=}, 공개 — 2026-11-19 클라이언트 BR-36). 상품 화면의 옵션 가격
 * 사본이다(견적 확정은 order 초안). 실패는 예외 — 호출자가 가격 없이(null) 두고 출처를 UNAVAILABLE 로 알린다(0원으로 바꾸지 않는다).
 */
@Component
public class PricingClient {

  /** 계약 상한(요청당 SKU 100개). */
  static final int MAX_SKUS = 100;

  public record Price(long basePrice, int priceVersion) {}

  private final RestClient pricing;
  private final ObjectMapper mapper;

  public PricingClient(
      RestClient.Builder builder,
      @Value("${pmart.content.pricing-base-url:http://pricing-service:8107}") String baseUrl,
      ObjectMapper mapper) {
    this.pricing = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 가격이 정해진 SKU 만 — 미설정(notSet)·모르는 SKU 는 빠진다. */
  public Map<String, Price> prices(List<String> skus) {
    Map<String, Price> out = new HashMap<>();
    List<String> distinct = skus.stream().distinct().toList();
    for (int from = 0; from < distinct.size(); from += MAX_SKUS) {
      List<String> chunk =
          new ArrayList<>(distinct.subList(from, Math.min(from + MAX_SKUS, distinct.size())));
      String raw =
          Downstream.call(
              "pricing-service prices",
              () ->
                  pricing
                      .get()
                      .uri(
                          u ->
                              u.path("/api/v1/prices")
                                  .queryParam("skus", String.join(",", chunk))
                                  .build())
                      .retrieve()
                      .body(String.class));
      for (JsonNode p : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        if (p.path("basePrice").isNumber()) {
          out.put(
              p.path("sku").asString(),
              new Price(p.path("basePrice").asLong(), p.path("priceVersion").asInt(0)));
        }
      }
    }
    return out;
  }
}
