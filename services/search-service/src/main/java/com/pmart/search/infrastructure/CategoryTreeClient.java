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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
 *
 * <p>2026-11-19(검색 성능 분석 1순위 ② — 사용자 "카테고리 들어갈 때도 느려"): 검색 요청이 다시 읽기를 기다리지 않는다. 만료된 사본은 그대로 쓰고 뒤에서
 * 한 번만 다시 읽는다(같은 키는 동시에 하나 — {@link #once}). 처음(사본이 아예 없을 때)만 기다리고, 그때도 동시 요청은 같은 읽기 하나를 기다린다. 사본
 * 없이 실패하면 빈 사본을 30초 둔다(결과마다 다시 부르지 않게). 필터 패널은 없는 것만 한꺼번에(병렬) 읽는다.
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
  static final Duration RETRY = Duration.ofSeconds(30);

  private final AtomicReference<Snapshot> cache = new AtomicReference<>();
  private final Map<String, Panel> panels = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
  private final Executor background;

  @org.springframework.beans.factory.annotation.Autowired
  public CategoryTreeClient(
      RestClient.Builder builder, SearchProperties props, ObjectMapper mapper, Clock clock) {
    this(builder, props, mapper, clock, Executors.newVirtualThreadPerTaskExecutor());
  }

  CategoryTreeClient(
      RestClient.Builder builder,
      SearchProperties props,
      ObjectMapper mapper,
      Clock clock,
      Executor background) {
    this.http = builder.clone().baseUrl(props.productBaseUrl()).build();
    this.mapper = mapper;
    this.clock = clock;
    this.background = background;
  }

  /** 같은 키의 읽기는 동시에 하나만 — 이미 돌고 있으면 그것을 돌려준다. 동기 실행기에서도 안전하게(맵 갱신 밖에서 지운다). */
  private CompletableFuture<Void> once(String key, Runnable load) {
    CompletableFuture<Void> f = new CompletableFuture<>();
    CompletableFuture<Void> running = loading.putIfAbsent(key, f);
    if (running != null) {
      return running;
    }
    try {
      background.execute(
          () -> {
            try {
              load.run();
            } catch (RuntimeException e) {
              log.warn("카테고리 사본 읽기 실패 {}: {}", key, e.toString());
            } finally {
              loading.remove(key, f);
              f.complete(null);
            }
          });
    } catch (RuntimeException e) { // 실행기가 거절 — 그 자리에서
      loading.remove(key, f);
      f.complete(null);
    }
    return f;
  }

  private static boolean stale(Instant fetchedAt, Instant now) {
    return !fetchedAt.plus(TTL).isAfter(now);
  }

  /** 카테고리 id → 이름 경로(상위부터). 모르면 빈 목록. */
  public List<String> path(String categoryId) {
    Snapshot s = current();
    return s == null ? List.of() : s.paths().getOrDefault(categoryId, List.of());
  }

  private @org.jspecify.annotations.Nullable Snapshot current() {
    Snapshot s = cache.get();
    if (s != null) {
      if (stale(s.fetchedAt(), clock.instant())) {
        once("tree", this::loadTree); // 뒤에서 — 이번 요청은 만료된 사본으로
      }
      return s;
    }
    once("tree", this::loadTree).join(); // 처음만 기다린다(동시 요청은 같은 읽기 하나)
    return cache.get();
  }

  /** 트리를 읽어 사본을 바꾼다. 실패하면 이전 사본(없으면 빈 사본)을 30초 뒤 다시 읽게 둔다. */
  void loadTree() {
    Snapshot s = cache.get();
    Instant now = clock.instant();
    try {
      String raw = fetchTree();
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
      cache.set(new Snapshot(Map.copyOf(paths), Map.copyOf(parents), List.copyOf(tops), now));
    } catch (RuntimeException e) {
      log.warn("카테고리 트리 조회 실패 — 이전 사본 사용(없으면 빈 경로): {}", e.toString());
      Instant retryAt = now.minus(TTL).plus(RETRY); // 30초 뒤 다시 시도
      cache.set(
          s == null
              ? new Snapshot(Map.of(), Map.of(), List.of(), retryAt)
              : new Snapshot(s.paths(), s.parents(), s.tops(), retryAt));
    }
  }

  /** 트리 읽기(시험이 바꿔 끼운다). */
  String fetchTree() {
    return Downstream.call(
        "product-service category tree",
        () -> http.get().uri("/api/v1/categories/tree").retrieve().body(String.class));
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
    // 사본이 없는 패널만 한꺼번에(병렬) 읽고 기다린다 — 최상위 17개를 차례로 읽던 것(첫 호출 283ms)
    List<CompletableFuture<Void>> missing = new java.util.ArrayList<>();
    for (String id : ids) {
      if (!panels.containsKey(id)) {
        missing.add(once("panel:" + id, () -> loadPanel(id)));
      }
    }
    if (!missing.isEmpty()) {
      CompletableFuture.allOf(missing.toArray(CompletableFuture[]::new)).join();
    }
    Instant now = clock.instant();
    Map<String, FilterGroup> merged = new java.util.LinkedHashMap<>();
    for (String id : ids) {
      Panel p = panels.get(id);
      if (p == null) {
        continue;
      }
      if (stale(p.fetchedAt(), now)) {
        once("panel:" + id, () -> loadPanel(id)); // 뒤에서 — 이번 요청은 만료된 사본으로
      }
      for (FilterGroup g : p.groups()) {
        merged.putIfAbsent(g.id(), g);
      }
    }
    return List.copyOf(merged.values());
  }

  /** 패널을 읽어 사본을 바꾼다. 실패하면 이전 사본(없으면 빈 목록)을 30초 뒤 다시 읽게 둔다. */
  void loadPanel(String categoryId) {
    Instant now = clock.instant();
    Panel p = panels.get(categoryId);
    List<FilterGroup> fresh = filterGroups(categoryId);
    if (fresh == null) {
      panels.put(
          categoryId, new Panel(p == null ? List.of() : p.groups(), now.minus(TTL).plus(RETRY)));
      return;
    }
    panels.put(categoryId, new Panel(fresh, now));
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
