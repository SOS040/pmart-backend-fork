package com.pmart.storefront.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.storefront.domain.LayoutRules.Banner;
import com.pmart.storefront.domain.LayoutRules.Body;
import com.pmart.storefront.domain.LayoutRules.Problem;
import com.pmart.storefront.domain.LayoutRules.Theme;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayoutRulesTest {

  static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
  static final Set<String> PRODUCTS = Set.of("P1", "P2");

  static List<String> reasons(Body b) {
    return LayoutRules.check(b, PRODUCTS::contains, "IMG-1"::equals).stream()
        .map(Problem::reason)
        .toList();
  }

  static Theme theme(String id, String title, List<String> products) {
    return new Theme(id, "THEME", title, null, products, 0, null, null);
  }

  static Banner banner(String alt, String linkType, String ref) {
    return new Banner("B1", "HOME_TOP", "IMG-1", alt, linkType, ref, 0, null, null);
  }

  @Test
  void validLayoutPasses() {
    Body ok =
        new Body(
            List.of(banner("겨울 제철 채소", "SECTION", "T-WINTER")),
            List.of(theme("T-WINTER", "겨울 제철 채소", List.of("P1", "P2"))),
            null);
    assertThat(reasons(ok)).isEmpty();
  }

  @Test
  void problems() {
    assertThat(reasons(Body.empty())).isEmpty(); // 철회 = 빈 새 버전
    Body bad =
        new Body(
            List.of(
                banner("주말 특가", "SECTION", "T-NONE"),
                new Banner("B1", "SIDE", "IMG-X", "안내", "NONE", "x", 0, T0, T0)),
            List.of(theme("T-A", "쿠폰 받고 30% off", List.of("P1", "P9", "P1"))),
            null);
    assertThat(reasons(bad))
        .containsExactlyInAnyOrder(
            "PROMOTION_NOT_ALLOWED",
            "INVALID_LINK",
            "DUPLICATE_ID",
            "INVALID_PLACEMENT",
            "IMAGE_NOT_FOUND",
            "INVALID_LINK",
            "INVALID_PERIOD",
            "PROMOTION_NOT_ALLOWED",
            "PRODUCT_NOT_PUBLISHED",
            "DUPLICATE_PRODUCT");
  }

  @Test
  void promotionWords() {
    assertThat(LayoutRules.promotional("100% 국내산 햇양파")).isFalse();
    assertThat(LayoutRules.promotional("30% OFF")).isTrue();
    assertThat(LayoutRules.promotional("1+1 행사")).isTrue();
    assertThat(LayoutRules.promotional("신선 보장")).isTrue();
    assertThat(LayoutRules.promotional("Wholesale")).isFalse();
  }

  @Test
  void activePeriod() {
    assertThat(LayoutRules.active(null, null, T0)).isTrue();
    assertThat(LayoutRules.active(T0.plusSeconds(1), null, T0)).isFalse();
    assertThat(LayoutRules.active(null, T0, T0)).isFalse();
    assertThat(LayoutRules.active(T0.minusSeconds(1), T0.plusSeconds(1), T0)).isTrue();
  }
}
