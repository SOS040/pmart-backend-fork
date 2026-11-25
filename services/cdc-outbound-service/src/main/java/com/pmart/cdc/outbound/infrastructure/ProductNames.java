package com.pmart.cdc.outbound.infrastructure;

import com.pmart.cdc.outbound.application.OutboundProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SKU → 상품 관리용 명칭(0.21.0, PC5 P-1 — MFC 와 같은 internalName). product-service {@code GET
 * /internal/skus?skus=}(최대 100건)를 10분 담아 둔다. 상품 서비스가 안 되면 이름 없이(null) — 화면 조회를 막지 않는다.
 */
@Component
public class ProductNames {

  private static final Logger log = LoggerFactory.getLogger(ProductNames.class);
  private static final Duration TTL = Duration.ofMinutes(10);

  private record Entry(String name, Instant at) {}

  private final Map<String, Entry> cache = new ConcurrentHashMap<>();
  private final RestClient http;
  private final ObjectMapper mapper;

  public ProductNames(RestClient.Builder builder, OutboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.mapper = mapper;
  }

  public Map<String, String> of(Collection<String> skus) {
    Map<String, String> out = new HashMap<>();
    List<String> miss = new ArrayList<>();
    Instant now = Instant.now();
    for (String s : skus.stream().distinct().toList()) {
      Entry e = cache.get(s);
      if (e != null && e.at().plus(TTL).isAfter(now)) {
        if (!e.name().isEmpty()) {
          out.put(s, e.name());
        }
      } else {
        miss.add(s);
      }
    }
    for (int i = 0; i < miss.size(); i += 100) {
      List<String> part = miss.subList(i, Math.min(miss.size(), i + 100));
      try {
        String body =
            http.get()
                .uri("/api/v1/internal/skus?skus={s}", String.join(",", part))
                .header("X-Source-System", "cdc-outbound-service")
                .retrieve()
                .body(String.class);
        JsonNode items = mapper.readTree(body == null ? "{}" : body).path("items");
        for (String s : part) {
          cache.put(s, new Entry("", now)); // 없는 SKU 도 잠시 담아 매번 부르지 않는다
        }
        for (JsonNode it : items) {
          String sku = it.path("sku").asString(null);
          String name = it.path("internalName").asString(null);
          if (sku != null && name != null && !name.isBlank()) {
            cache.put(sku, new Entry(name, now));
            out.put(sku, name);
          }
        }
      } catch (RuntimeException e) {
        log.warn("상품 이름 조회 실패(이름 없이 응답): {}", e.toString());
        return out;
      }
    }
    return out;
  }
}
