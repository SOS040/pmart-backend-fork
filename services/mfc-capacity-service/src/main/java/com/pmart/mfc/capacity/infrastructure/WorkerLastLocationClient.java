package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.capacity.application.PlanProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 작업자마다 마지막 스캔 칸(작업자 도면 위치, 0.34.0) — mfc-fulfillment·mfc-inbound 의 {@code GET
 * /internal/worker-last-locations}(내부 호출, 토큰 없음 — ADR-0026). 주소는 배정 추천과 같은 {@code
 * pmart.capacity.plan.*}. 주소가 없거나 조회가 실패한 원천은 결과에서 빠지고 이름만 알린다.
 */
@Component
public class WorkerLastLocationClient {

  /** 한 건(원천 이름 + 원천이 준 값). */
  public record Item(
      String source, String workerId, String locationCode, String activityType, Instant at) {}

  public record Collected(List<Item> items, List<String> unavailable) {}

  private final Map<String, @Nullable RestClient> sources = new LinkedHashMap<>();
  private final ObjectMapper mapper;

  public WorkerLastLocationClient(
      RestClient.Builder builder, PlanProperties plan, ObjectMapper mapper) {
    this.mapper = mapper;
    sources.put("mfc-fulfillment-service", client(builder, plan.mfcFulfillmentBaseUrl()));
    sources.put("mfc-inbound-service", client(builder, plan.mfcInboundBaseUrl()));
  }

  private static @Nullable RestClient client(RestClient.Builder b, @Nullable String url) {
    return url == null || url.isBlank() ? null : b.clone().baseUrl(url).build();
  }

  public Collected collect(String mfcId, Instant since) {
    List<Item> items = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    sources.forEach(
        (name, http) -> {
          if (http == null) {
            unavailable.add(name);
            return;
          }
          try {
            String raw =
                Downstream.call(
                    name + " worker-last-locations",
                    () ->
                        http.get()
                            .uri(
                                "/api/v1/internal/worker-last-locations?mfcId={m}&since={s}",
                                mfcId,
                                since.toString())
                            .retrieve()
                            .body(String.class));
            for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
              items.add(
                  new Item(
                      name,
                      n.path("workerId").asString(),
                      n.path("locationCode").asString(),
                      n.path("activityType").asString(),
                      Instant.parse(n.path("at").asString())));
            }
          } catch (RuntimeException e) {
            unavailable.add(name); // 일부 원천 실패 — 나머지는 보인다
          }
        });
    return new Collected(items, unavailable);
  }
}
