package com.pmart.product.api;

import com.pmart.product.api.generated.CategoriesApi;
import com.pmart.product.api.generated.model.GetCategoryFilters200Response;
import com.pmart.product.api.generated.model.GetCategoryTree200Response;
import com.pmart.product.application.CategoryTreeService;
import com.pmart.product.infrastructure.CategoryStore.VersionRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CategoriesApi — 발효 카테고리 트리(4단계)와 노드별 필터 패널 조회. */
@RestController
public class CategoriesController implements CategoriesApi {

  private final CategoryTreeService trees;
  private final RegistrationMapping mapping;

  public CategoriesController(CategoryTreeService trees, RegistrationMapping mapping) {
    this.trees = trees;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<GetCategoryTree200Response> getCategoryTree(@Nullable Integer version) {
    VersionRow v = trees.readable(version);
    List<Map<String, Object>> nodes =
        trees.nodesForDisplay(v.version()).stream()
            .map(
                n -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("categoryId", n.categoryId());
                  m.put("parentId", n.parentId());
                  m.put("level", n.level());
                  m.put("section", n.section());
                  m.put("name", n.name());
                  m.put("path", n.path());
                  m.put("sortOrder", n.sortOrder());
                  m.put("exposed", n.exposed());
                  return m;
                })
            .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", v.version());
    body.put("effectiveFrom", v.effectiveFrom() == null ? null : v.effectiveFrom().toString());
    body.put("nodes", nodes);
    return ResponseEntity.ok(mapping.convert(body, GetCategoryTree200Response.class));
  }

  @Override
  public ResponseEntity<GetCategoryFilters200Response> getCategoryFilters(
      String categoryId, @Nullable Integer version) {
    VersionRow v = trees.readable(version);
    List<Map<String, Object>> groups =
        trees.filters(v.version(), categoryId).stream()
            .map(
                g -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("filterGroupId", g.filterGroupId());
                  m.put("name", g.name());
                  m.put("selectionMode", g.selectionMode());
                  m.put("common", g.common());
                  m.put("options", g.options());
                  return m;
                })
            .toList();
    Map<String, Object> body =
        Map.of("categoryId", categoryId, "version", v.version(), "groups", groups);
    return ResponseEntity.ok(mapping.convert(body, GetCategoryFilters200Response.class));
  }
}
