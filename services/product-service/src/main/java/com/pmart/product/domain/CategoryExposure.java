package com.pmart.product.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 카테고리 노출 계산(BR-94 R4, 2026-11-24): 상품이 걸린 노드와 그 위 노드 모두. */
public final class CategoryExposure {

  private CategoryExposure() {}

  /**
   * @param parents 노드 id → 부모 id(TOP 은 null)
   * @param linked 상품이 걸린 카테고리 id(트리에 없는 id 는 무시)
   * @return 그 노드 또는 아래 노드에 상품이 있는 노드 id
   */
  public static Set<String> withProducts(
      Map<String, @Nullable String> parents, Set<String> linked) {
    Set<String> out = new HashSet<>();
    for (String id : linked) {
      String cur = id;
      while (cur != null && parents.containsKey(cur) && out.add(cur)) {
        cur = parents.get(cur);
      }
    }
    return out;
  }
}
