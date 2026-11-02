package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 가용 조회({@code /internal/availability}, 토큰 없음 — ADR-0026)로 센터 취급 여부(handlingEnabled)
 * 사본을 얻는다. 실패하면 빈 결과 — 호출자는 불명(null)으로 둔다(false·품절로 바꾸지 않는다).
 */
@Component
public class AvailabilityClient {

  /** 계약 상한(요청당 SKU 100개). */
  static final int MAX_SKUS = 100;

  private static final Logger log = LoggerFactory.getLogger(AvailabilityClient.class);

  private final RestClient http;
  private final ObjectMapper mapper;

  public AvailabilityClient(
      RestClient.Builder builder, SearchProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.inventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** SKU → 취급 여부. 응답에 없는 SKU 는 빠진다(불명). */
  public Map<String, Boolean> carried(String mfcId, List<String> skus) {
    Map<String, Boolean> out = new HashMap<>();
    try {
      for (int i = 0; i < skus.size(); i += MAX_SKUS) {
        List<String> chunk = skus.subList(i, Math.min(skus.size(), i + MAX_SKUS));
        String raw =
            Downstream.call(
                "mfc-inventory-service availability",
                () ->
                    http.get()
                        .uri(
                            b -> {
                              var u =
                                  b.path("/api/v1/internal/availability")
                                      .queryParam("mfcId", mfcId);
                              chunk.forEach(s -> u.queryParam("sku", s));
                              return u.build();
                            })
                        .retrieve()
                        .body(String.class));
        for (JsonNode a : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
          out.put(a.path("sku").asString(), a.path("handlingEnabled").asBoolean(false));
        }
      }
    } catch (RuntimeException e) {
      log.warn("취급 여부 조회 실패 mfcId={} — 불명으로 둔다: {}", mfcId, e.toString());
      return Map.of();
    }
    return out;
  }
}
