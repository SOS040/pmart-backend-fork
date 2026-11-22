package com.pmart.order.application;

import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 0.20.0 O-11(2026-11-23): 주문 지표의 카테고리 — 주문 줄에는 카테고리 사본이 없어 product 의 지금 값을 쓴다. 공개 트리(이름·부모)와 상품
 * 목록(대표 카테고리)을 5분 사본으로 둔다. 읽기 실패면 이전 사본, 사본이 없으면 빈 것(카테고리 null — 지표는 실패하지 않음).
 */
@Component
public class ProductCategories {

  private static final Logger log = LoggerFactory.getLogger(ProductCategories.class);
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final int MAX_PAGES = 200;

  /** 상품 → 대표 카테고리, 카테고리 → 이름 경로·부모. */
  public record Snapshot(
      Map<String, String> categoryOfProduct,
      Map<String, String> nameOf,
      Map<String, String> parentOf,
      Instant loadedAt) {

    static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Instant.EPOCH);

    /** 그 카테고리와 모든 하위 카테고리. */
    public Set<String> withDescendants(String categoryId) {
      Map<String, List<String>> children = new HashMap<>();
      parentOf.forEach((c, p) -> children.computeIfAbsent(p, k -> new ArrayList<>()).add(c));
      Set<String> out = new HashSet<>();
      Deque<String> todo = new ArrayDeque<>(List.of(categoryId));
      while (!todo.isEmpty()) {
        String c = todo.pop();
        if (out.add(c)) {
          todo.addAll(children.getOrDefault(c, List.of()));
        }
      }
      return out;
    }

    /** 그 카테고리(하위 포함)에 대표 카테고리가 든 상품. */
    public Set<String> productsIn(String categoryId) {
      Set<String> cats = withDescendants(categoryId);
      Set<String> out = new HashSet<>();
      categoryOfProduct.forEach(
          (p, c) -> {
            if (cats.contains(c)) {
              out.add(p);
            }
          });
      return out;
    }
  }

  private final Upstreams upstreams;
  private final Clock clock;
  private volatile Snapshot snapshot = Snapshot.EMPTY;

  public ProductCategories(Upstreams upstreams, Clock clock) {
    this.upstreams = upstreams;
    this.clock = clock;
  }

  public Snapshot current() {
    Snapshot s = snapshot;
    if (Duration.between(s.loadedAt(), clock.instant()).compareTo(TTL) < 0) {
      return s;
    }
    synchronized (this) {
      if (snapshot != s) {
        return snapshot;
      }
      try {
        snapshot = load();
      } catch (RuntimeException e) {
        log.warn("상품 카테고리 사본 읽기 실패(이전 사본 사용): {}", e.toString());
        // 실패해도 곧바로 다시 부르지 않게 시각만 늦춘다
        snapshot = new Snapshot(s.categoryOfProduct(), s.nameOf(), s.parentOf(), clock.instant());
      }
      return snapshot;
    }
  }

  private Snapshot load() {
    Map<String, String> names = new HashMap<>();
    Map<String, String> parents = new HashMap<>();
    for (JsonNode n : upstreams.categoryTree().path("nodes")) {
      String id = n.path("categoryId").asString(null);
      if (id == null) {
        continue;
      }
      String path = n.path("path").asString(null);
      names.put(id, path == null || path.isBlank() ? n.path("name").asString(id) : path);
      String parent = n.path("parentId").asString(null);
      if (parent != null && !parent.isBlank()) {
        parents.put(id, parent);
      }
    }
    Map<String, String> products = new HashMap<>();
    String cursor = null;
    for (int i = 0; i < MAX_PAGES; i++) {
      JsonNode page = upstreams.productsPage(cursor);
      for (JsonNode p : page.path("items")) {
        String id = p.path("productId").asString(null);
        String cat = p.path("primaryCategoryId").asString(null);
        if (id != null && cat != null && !cat.isBlank()) {
          products.put(id, cat);
        }
      }
      cursor = page.path("page").path("nextCursor").asString(null);
      if (cursor == null || cursor.isBlank()) {
        break;
      }
    }
    return new Snapshot(
        Map.copyOf(products), Map.copyOf(names), Map.copyOf(parents), clock.instant());
  }

  /** 다음 조회 때 다시 읽게 한다(시험·운영 재읽기). */
  public void invalidate() {
    snapshot = Snapshot.EMPTY;
  }

  public @Nullable String nameOf(Snapshot s, @Nullable String categoryId) {
    return categoryId == null ? null : s.nameOf().get(categoryId);
  }
}
