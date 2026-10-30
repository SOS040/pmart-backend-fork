package com.pmart.product.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 카테고리 트리·필터 묶음(ADR-0015). 상위 > 하위 > 칩1 > 칩2 순수 트리이고 노드마다 필터 패널(그룹 id 목록, 화면 순서)을 가진다. 원천 형식(참고
 * 자료)을 평탄한 행으로 바꾸며 구조 오류를 모두 모아 돌려준다.
 */
public final class CategoryTree {

  public enum Level {
    TOP,
    SUB,
    CHIP1,
    CHIP2;

    @Nullable Level child() {
      return this == CHIP2 ? null : values()[ordinal() + 1];
    }

    static Level parse(String raw) {
      return valueOf(raw.toUpperCase(Locale.ROOT));
    }
  }

  public enum SelectionMode {
    MULTIPLE,
    TOGGLE,
    MIXED
  }

  /** 원천 노드(참고 자료 categories.json). */
  public record SourceNode(
      String id,
      String name,
      String level,
      @Nullable String section,
      List<String> filters,
      List<SourceNode> children) {}

  /** 원천 필터 그룹(참고 자료 filters.json definitions). */
  public record SourceFilter(
      String id, String group, String selectionMode, boolean common, List<String> options) {}

  public record Node(
      String categoryId,
      @Nullable String parentId,
      Level level,
      @Nullable String section,
      String name,
      String path,
      int sortOrder) {}

  public record NodeFilter(String categoryId, String filterGroupId, int sortOrder) {}

  public record FilterGroup(
      String filterGroupId,
      String name,
      SelectionMode selectionMode,
      boolean common,
      List<String> options) {}

  public record Parsed(
      List<Node> nodes,
      List<NodeFilter> nodeFilters,
      List<FilterGroup> filterGroups,
      Map<Level, Integer> countsByLevel) {}

  /** 구조 오류. 하나라도 있으면 저장하지 않는다. */
  public static final class InvalidTreeException extends RuntimeException {
    private final List<String> errors;

    InvalidTreeException(List<String> errors) {
      super(
          "카테고리 트리 오류 "
              + errors.size()
              + "건: "
              + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
      this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
      return errors;
    }
  }

  private CategoryTree() {}

  public static Parsed parse(List<SourceNode> roots, List<SourceFilter> filters) {
    List<String> errors = new ArrayList<>();
    List<FilterGroup> groups = new ArrayList<>();
    Set<String> groupIds = new HashSet<>();
    for (SourceFilter f : filters) {
      if (!groupIds.add(f.id())) {
        errors.add("필터 그룹 id 중복: " + f.id());
        continue;
      }
      SelectionMode mode;
      try {
        mode = SelectionMode.valueOf(f.selectionMode().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        errors.add("선택 방식 오류 " + f.id() + ": " + f.selectionMode());
        continue;
      }
      groups.add(new FilterGroup(f.id(), f.group(), mode, f.common(), List.copyOf(f.options())));
    }

    List<Node> nodes = new ArrayList<>();
    List<NodeFilter> nodeFilters = new ArrayList<>();
    Map<String, String> seen = new HashMap<>();
    Map<Level, Integer> counts = new EnumMap<>(Level.class);
    int order = 0;
    for (SourceNode r : roots) {
      walk(r, null, null, Level.TOP, order++, groupIds, nodes, nodeFilters, seen, counts, errors);
    }
    if (nodes.isEmpty()) {
      errors.add("카테고리 노드가 없습니다.");
    }
    if (!errors.isEmpty()) {
      throw new InvalidTreeException(errors);
    }
    return new Parsed(List.copyOf(nodes), List.copyOf(nodeFilters), List.copyOf(groups), counts);
  }

  private static void walk(
      SourceNode n,
      @Nullable String parentId,
      @Nullable String parentPath,
      Level expected,
      int sortOrder,
      Set<String> groupIds,
      List<Node> nodes,
      List<NodeFilter> nodeFilters,
      Map<String, String> seen,
      Map<Level, Integer> counts,
      List<String> errors) {
    Level level;
    try {
      level = Level.parse(n.level());
    } catch (IllegalArgumentException e) {
      errors.add("단계 오류 " + n.id() + ": " + n.level());
      return;
    }
    if (level != expected) {
      errors.add("단계 순서 오류 " + n.id() + ": " + expected + " 자리에 " + level);
    }
    if (n.id() == null || n.id().isBlank() || n.name() == null || n.name().isBlank()) {
      errors.add("id·이름 없는 노드(부모 " + parentId + ")");
      return;
    }
    if (seen.putIfAbsent(n.id(), n.name()) != null) {
      errors.add("카테고리 id 중복: " + n.id());
      return;
    }
    String path = parentPath == null ? n.name().strip() : parentPath + " > " + n.name().strip();
    nodes.add(
        new Node(
            n.id(),
            parentId,
            level,
            level == Level.TOP ? n.section() : null,
            n.name().strip(),
            path,
            sortOrder));
    counts.merge(level, 1, Integer::sum);
    int fo = 0;
    Set<String> nodeGroups = new HashSet<>();
    for (String g : n.filters()) {
      if (!groupIds.contains(g)) {
        errors.add(n.id() + " 의 필터 그룹이 정의에 없습니다: " + g);
      } else if (nodeGroups.add(g)) {
        nodeFilters.add(new NodeFilter(n.id(), g, fo++));
      }
    }
    Level childLevel = level.child();
    int co = 0;
    for (SourceNode c : n.children()) {
      if (childLevel == null) {
        errors.add("칩2 아래에는 노드를 둘 수 없습니다: " + c.id());
        continue;
      }
      walk(c, n.id(), path, childLevel, co++, groupIds, nodes, nodeFilters, seen, counts, errors);
    }
  }
}
