package com.pmart.search.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.search.application.SearchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product-service 공개 카테고리 트리({@code GET /categories/tree}, 계약 security: [])의 이름 경로 사본. 5분 동안 메모리에
 * 두고 다시 읽는다(트리는 승인으로만 바뀐다). 조회에 실패하면 이전 사본을 쓰고, 사본도 없으면 빈 경로 — 검색을 실패시키지 않는다. id 를 이름 자리에 넣지 않는다.
 */
@Component
public class CategoryTreeClient {

  static final Duration TTL = Duration.ofMinutes(5);
  private static final Logger log = LoggerFactory.getLogger(CategoryTreeClient.class);

  private record Snapshot(Map<String, List<String>> paths, Instant fetchedAt) {}

  private final RestClient http;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicReference<Snapshot> cache = new AtomicReference<>();

  public CategoryTreeClient(
      RestClient.Builder builder, SearchProperties props, ObjectMapper mapper, Clock clock) {
    this.http = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 카테고리 id → 이름 경로(상위부터). 모르면 빈 목록. */
  public List<String> path(String categoryId) {
    Snapshot s = current();
    return s == null ? List.of() : s.paths().getOrDefault(categoryId, List.of());
  }

  private Snapshot current() {
    Snapshot s = cache.get();
    Instant now = clock.instant();
    if (s != null && s.fetchedAt().plus(TTL).isAfter(now)) {
      return s;
    }
    try {
      String raw =
          Downstream.call(
              "product-service category tree",
              () -> http.get().uri("/api/v1/categories/tree").retrieve().body(String.class));
      Map<String, List<String>> paths = new HashMap<>();
      for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("nodes")) {
        String path = n.path("path").asString("");
        if (!path.isBlank()) {
          paths.put(
              n.path("categoryId").asString(),
              Arrays.stream(path.split(">")).map(String::strip).filter(x -> !x.isEmpty()).toList());
        }
      }
      Snapshot fresh = new Snapshot(Map.copyOf(paths), now);
      cache.set(fresh);
      return fresh;
    } catch (RuntimeException e) {
      log.warn("카테고리 트리 조회 실패 — 이전 사본 사용(없으면 빈 경로): {}", e.toString());
      if (s != null) {
        cache.set(new Snapshot(s.paths(), now.minus(TTL).plusSeconds(30))); // 30초 뒤 다시 시도
      }
      return s;
    }
  }

  /** 카테고리 필터 패널의 그룹 하나(이름·선택 방식·옵션 순서). */
  public record FilterGroup(String id, String name, boolean multiSelect, List<String> options) {}

  /**
   * product-service 공개 {@code GET /categories/{id}/filters} — 필터 이름·선택 방식·옵션 순서. 실패하면 빈 목록(필터 이름 대신
   * 그룹 id 를 보여 준다, 검색을 실패시키지 않는다).
   */
  public List<FilterGroup> filterGroups(String categoryId) {
    try {
      String raw =
          Downstream.call(
              "product-service category filters",
              () ->
                  http.get()
                      .uri("/api/v1/categories/{id}/filters", categoryId)
                      .retrieve()
                      .onStatus(s -> s.value() == 404, (q, r) -> {})
                      .body(String.class));
      List<FilterGroup> out = new java.util.ArrayList<>();
      for (JsonNode g : mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw).path("groups")) {
        List<String> options = new java.util.ArrayList<>();
        g.path("options").forEach(o -> options.add(o.asString()));
        out.add(
            new FilterGroup(
                g.path("filterGroupId").asString(),
                g.path("name").asString(g.path("filterGroupId").asString()),
                !"TOGGLE".equals(g.path("selectionMode").asString()),
                List.copyOf(options)));
      }
      return out;
    } catch (RuntimeException e) {
      log.warn("카테고리 필터 조회 실패 — 그룹 id 로 표시: {}", e.toString());
      return List.of();
    }
  }
}
