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

  /** 이름 경로, 부모(없으면 키 없음), 최상위 노드(트리 순서). */
  private record Snapshot(
      Map<String, List<String>> paths,
      Map<String, String> parents,
      List<String> tops,
      Instant fetchedAt) {}

  private record Panel(List<FilterGroup> groups, Instant fetchedAt) {}

  private final RestClient http;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicReference<Snapshot> cache = new AtomicReference<>();
  private final Map<String, Panel> panels = new java.util.concurrent.ConcurrentHashMap<>();

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
      Map<String, String> parents = new HashMap<>();
      List<String> tops = new java.util.ArrayList<>();
      for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("nodes")) {
        String id = n.path("categoryId").asString("");
        String path = n.path("path").asString("");
        if (!path.isBlank()) {
          paths.put(
              id,
              Arrays.stream(path.split(">")).map(String::strip).filter(x -> !x.isEmpty()).toList());
        }
        String parent = n.path("parentId").asString("");
        if (!parent.isBlank()) {
          parents.put(id, parent);
        } else if ("TOP".equals(n.path("level").asString())
            || (!id.isBlank() && !id.contains("-"))) {
          tops.add(id); // level 이 없으면 구조 id 로 판단(최상위는 '-' 없음 — t01, p03)
        }
      }
      Snapshot fresh = new Snapshot(Map.copyOf(paths), Map.copyOf(parents), List.copyOf(tops), now);
      cache.set(fresh);
      return fresh;
    } catch (RuntimeException e) {
      log.warn("카테고리 트리 조회 실패 — 이전 사본 사용(없으면 빈 경로): {}", e.toString());
      if (s != null) {
        cache.set(
            new Snapshot(
                s.paths(), s.parents(), s.tops(), now.minus(TTL).plusSeconds(30))); // 30초 뒤 다시 시도
      }
      return s;
    }
  }

  /** 카테고리 필터 패널의 그룹 하나(이름·선택 방식·옵션 순서). */
  public record FilterGroup(String id, String name, boolean multiSelect, List<String> options) {}

  /**
   * 필터 이름을 찾을 그룹 목록. 패널은 노드에 직접 붙은 것만 내려오므로(상속 없음) 그 카테고리와 조상의 패널을 가까운 것부터 합친다(같은 그룹이면 가까운 쪽).
   * 카테고리가 없으면 최상위 노드들의 패널을 합친다 — 하위 노드에만 붙은 그룹은 이때 이름을 못 찾고 그룹 id 로 보인다.
   */
  public List<FilterGroup> filterGroupsFor(@org.jspecify.annotations.Nullable String categoryId) {
    Snapshot s = current();
    List<String> ids = new java.util.ArrayList<>();
    if (categoryId == null || categoryId.isBlank()) {
      if (s != null) {
        ids.addAll(s.tops());
      }
    } else {
      for (String c = categoryId; c != null && !ids.contains(c) && ids.size() < 8; ) {
        ids.add(c);
        c = s == null ? null : s.parents().get(c);
      }
    }
    Map<String, FilterGroup> merged = new java.util.LinkedHashMap<>();
    for (String id : ids) {
      for (FilterGroup g : cachedPanel(id)) {
        merged.putIfAbsent(g.id(), g);
      }
    }
    return List.copyOf(merged.values());
  }

  private List<FilterGroup> cachedPanel(String categoryId) {
    Instant now = clock.instant();
    Panel p = panels.get(categoryId);
    if (p != null && p.fetchedAt().plus(TTL).isAfter(now)) {
      return p.groups();
    }
    List<FilterGroup> fresh = filterGroups(categoryId);
    if (fresh == null) { // 실패: 이전 사본(없으면 빈 목록), 30초 뒤 다시
      List<FilterGroup> prev = p == null ? List.of() : p.groups();
      panels.put(categoryId, new Panel(prev, now.minus(TTL).plusSeconds(30)));
      return prev;
    }
    panels.put(categoryId, new Panel(fresh, now));
    return fresh;
  }

  /**
   * product-service 공개 {@code GET /categories/{id}/filters} — 필터 이름·선택 방식·옵션 순서. 실패하면 null(호출 쪽이 이전
   * 사본 또는 그룹 id 로 표시, 검색을 실패시키지 않는다). 없는 노드(404)는 빈 목록.
   */
  @org.jspecify.annotations.Nullable
  List<FilterGroup> filterGroups(String categoryId) {
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
      log.warn("카테고리 필터 조회 실패 — 이전 사본 또는 그룹 id 로 표시: {}", e.toString());
      return null;
    }
  }
}
