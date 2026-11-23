package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.infrastructure.CategoryTreeClient.FilterGroup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /search/filters} 의 속성 줄 만들기(2026-11-22 BR-81).
 *
 * <ul>
 *   <li>색인 집계 키는 그룹 id. 이름은 그 카테고리·조상 패널(nearby)에서, 없으면 모든 패널(all)에서 찾는다 — 하위 카테고리 상품의 그룹이 id 로 보이던
 *       것.
 *   <li>같은 이름의 그룹(카테고리마다 따로 정의된 "중량" 등)은 한 줄로 묶는다 — 키 {@code n:<이름>}, 같은 옵션은 건수를 더한다. 검색 {@code
 *       filters=n:<이름>.<옵션>} 은 그 이름의 그룹 id 모두로 거른다({@link SearchRules#filterGroupIds}).
 *   <li>줄 순서 = nearby 패널 순서, 그 뒤 나머지(처음 나온 순서). 옵션 순서 = 그 이름의 첫 그룹(가까운 패널 우선) 옵션 순서, 없는 것은 뒤.
 *   <li>이름을 끝내 못 찾으면 그룹 id 그대로(전과 같음).
 * </ul>
 */
public final class FilterAttributes {

  public record Value(String value, long count) {}

  public record Attribute(String key, String label, boolean multiSelect, List<Value> values) {}

  private FilterAttributes() {}

  public static List<Attribute> build(
      List<ProductSearch.Facet> facets, List<FilterGroup> nearby, Map<String, FilterGroup> all) {
    Map<String, FilterGroup> byId = new LinkedHashMap<>(all);
    nearby.forEach(g -> byId.put(g.id(), g)); // 가까운 패널이 이긴다
    record Acc(String label, boolean multi, List<String> order, Map<String, Long> counts) {}
    Map<String, Acc> rows = new LinkedHashMap<>();
    for (FilterGroup g : nearby) { // 패널 순서를 먼저 잡는다
      String key = SearchRules.filterKey(g.id(), g.name());
      rows.putIfAbsent(
          key,
          new Acc(g.name(), g.multiSelect(), new ArrayList<>(g.options()), new LinkedHashMap<>()));
    }
    for (ProductSearch.Facet f : facets) {
      if (com.pmart.search.domain.LabelRules.NUTRITION_KEY.equals(f.key())) { // 영양(products-v9)
        rows.computeIfAbsent(
                f.key(),
                k ->
                    new Acc(
                        com.pmart.search.domain.LabelRules.NUTRITION_LABEL,
                        true,
                        new ArrayList<>(com.pmart.search.domain.LabelRules.NUTRITION_OPTIONS),
                        new LinkedHashMap<>()))
            .counts()
            .merge(f.value(), f.count(), Long::sum);
        continue;
      }
      if (SearchRules.ORIGIN_KEY.equals(f.key())) { // 원산지 — 패널이 아니라 콘텐츠의 원산지 줄(products-v8)
        rows.computeIfAbsent(
                f.key(),
                k ->
                    new Acc(
                        SearchRules.ORIGIN_LABEL,
                        true,
                        new ArrayList<>(SearchRules.ORIGIN_OPTIONS),
                        new LinkedHashMap<>()))
            .counts()
            .merge(f.value(), f.count(), Long::sum);
        continue;
      }
      FilterGroup g = byId.get(f.key());
      String name = g == null ? f.key() : g.name();
      String key = g == null ? f.key() : SearchRules.filterKey(g.id(), name);
      Acc acc =
          rows.computeIfAbsent(
              key,
              k ->
                  new Acc(
                      name,
                      g == null || g.multiSelect(),
                      g == null ? new ArrayList<>() : new ArrayList<>(g.options()),
                      new LinkedHashMap<>()));
      if (g != null) {
        g.options().stream().filter(o -> !acc.order().contains(o)).forEach(acc.order()::add);
      }
      acc.counts().merge(f.value(), f.count(), Long::sum);
    }
    List<Attribute> out = new ArrayList<>();
    rows.forEach(
        (key, acc) -> {
          if (acc.counts().isEmpty()) {
            return;
          }
          List<Value> values =
              acc.counts().entrySet().stream()
                  .sorted(
                      Comparator.comparingInt(
                          e -> {
                            int i = acc.order().indexOf(e.getKey());
                            return i < 0 ? Integer.MAX_VALUE : i;
                          }))
                  .map(e -> new Value(e.getKey(), e.getValue()))
                  .toList();
          out.add(new Attribute(key, acc.label(), acc.multi(), values));
        });
    return out;
  }
}
