package com.pmart.customer.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.customer.domain.WishlistRules.Option;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * product-content {@code GET /product-contents?productIds=&mfcId=}(상품 카드 묶음, 최대 100건) — 찜 목록
 * 표시용(ADR-0081). 고객 요청이라 공용 RestClient.Builder(토큰 전파)를 쓴다. 실패하면 빈 결과(표시만 비움 — 찜 목록은 그대로).
 */
@Component
public class ProductCardClient {

  private static final Logger log = LoggerFactory.getLogger(ProductCardClient.class);
  static final int BATCH = 100;

  /** 카드 한 장(표시에 필요한 칸만). */
  public record Card(@Nullable String name, @Nullable String imageUrl, List<Option> options) {}

  private final RestClient http;

  public ProductCardClient(
      RestClient.Builder builder,
      @Value("${pmart.customer.content-base-url:http://product-content-service:8102}")
          String baseUrl) {
    this.http = builder.clone().baseUrl(baseUrl).build();
  }

  public Map<String, Card> cards(List<String> productIds, @Nullable String mfcId) {
    Map<String, Card> out = new HashMap<>();
    for (int i = 0; i < productIds.size(); i += BATCH) {
      List<String> chunk = productIds.subList(i, Math.min(productIds.size(), i + BATCH));
      try {
        JsonNode r =
            Downstream.call(
                "product-content-service cards",
                () ->
                    http.get()
                        .uri(
                            u -> {
                              u.path("/api/v1/product-contents")
                                  .queryParam("productIds", String.join(",", chunk));
                              if (mfcId != null) {
                                u.queryParam("mfcId", mfcId);
                              }
                              return u.build();
                            })
                        .retrieve()
                        .body(JsonNode.class));
        if (r == null) {
          continue;
        }
        for (JsonNode c : r.path("items")) {
          List<Option> options = new ArrayList<>();
          for (JsonNode o : c.path("options")) {
            JsonNode price = o.path("price").path("basePrice");
            options.add(
                new Option(
                    text(o, "saleState"),
                    text(o.path("availability"), "state"),
                    price.isNumber() ? price.asLong() : null));
          }
          JsonNode img = c.path("mainImage").path("url");
          out.put(
              c.path("productId").asString(),
              new Card(text(c, "name"), img.isString() ? img.asString() : null, options));
        }
      } catch (RuntimeException e) {
        log.warn("찜 목록 상품 카드 조회 실패: {}", e.getClass().getSimpleName());
      }
    }
    return out;
  }

  private static @Nullable String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asString() : null;
  }
}
