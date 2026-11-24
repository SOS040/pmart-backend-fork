package com.pmart.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CategoryExposureTest {

  @Test
  void nodeIsShownWhenItOrADescendantHasProducts() {
    Map<String, @Nullable String> parents = new HashMap<>();
    parents.put("p01", null);
    parents.put("p01-c01", "p01");
    parents.put("p01-c01-l01", "p01-c01");
    parents.put("p01-c02", "p01");
    parents.put("p02", null);
    var shown = CategoryExposure.withProducts(parents, Set.of("p01-c01-l01", "x99"));
    assertThat(shown).containsExactlyInAnyOrder("p01", "p01-c01", "p01-c01-l01");
    assertThat(CategoryExposure.withProducts(parents, Set.of())).isEmpty();
  }
}
