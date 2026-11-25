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
    // 0.9.0: 할인·쿠폰·특가 문구는 허용 — 근거 없는 최저가·신선 보장만 막는다
    assertThat(reasons(bad))
        .containsExactlyInAnyOrder(
            "INVALID_LINK",
            "DUPLICATE_ID",
            "INVALID_PLACEMENT",
            "IMAGE_NOT_FOUND",
            "INVALID_LINK",
            "INVALID_PERIOD",
            "PRODUCT_NOT_PUBLISHED",
            "DUPLICATE_PRODUCT");
  }

  @Test
  void promotionWords() {
    assertThat(LayoutRules.promotional("100% 국내산 햇양파")).isFalse();
    assertThat(LayoutRules.promotional("30% OFF")).isFalse(); // 0.9.0 허용
    assertThat(LayoutRules.promotional("MD 특가 1+1")).isFalse();
    assertThat(LayoutRules.promotional("신선 보장")).isTrue();
    assertThat(LayoutRules.promotional("업계 최저가")).isTrue();
    assertThat(LayoutRules.promotional("Wholesale")).isFalse();
  }

  @Test
  void activePeriod() {
    assertThat(LayoutRules.active(null, null, T0)).isTrue();
    assertThat(LayoutRules.active(T0.plusSeconds(1), null, T0)).isFalse();
    assertThat(LayoutRules.active(null, T0, T0)).isFalse();
    assertThat(LayoutRules.active(T0.minusSeconds(1), T0.plusSeconds(1), T0)).isTrue();
  }

  /** 0.9.0: 타이머는 끝 시각 필수, 자동 랭킹은 상품 비움·autoSize 1~30, 일반 테마는 상품 필수, MD 탭은 있는 테마만·한 테마 한 탭. */
  @Test
  void timerAutoThemesAndTabs() {
    Theme timerNoEnd =
        new Theme("T-DEAL", "THEME", "MD 특가", null, List.of("P1"), 0, null, null, true, null);
    Theme autoOk =
        new Theme("T-HOT", "RANKING_AUTO", "실시간 인기 랭킹", null, List.of(), 1, null, T0, true, 10);
    Theme autoBad =
        new Theme("T-HOT2", "RANKING_AUTO", "랭킹", null, List.of("P1"), 2, null, null, false, 40);
    Theme empty =
        new Theme("T-EMPTY", "THEME", "빈 테마", null, List.of(), 3, null, null, false, null);
    LayoutRules.Tab tab =
        new LayoutRules.Tab("X-DEAL", "특가", List.of("T-DEAL", "T-NONE"), 0, null, null, false);
    LayoutRules.Tab tab2 =
        new LayoutRules.Tab("X-DEAL", "또", List.of("T-DEAL"), 1, null, null, true);
    Body body =
        new Body(List.of(), List.of(timerNoEnd, autoOk, autoBad, empty), List.of(tab, tab2), null);
    assertThat(reasons(body))
        .containsExactlyInAnyOrder(
            "TIMER_NEEDS_END",
            "INVALID_AUTO_THEME",
            "EMPTY_THEME",
            "UNKNOWN_SECTION",
            "DUPLICATE_ID",
            "TIMER_NEEDS_END",
            "DUPLICATE_ID");
    assertThat(body.tabbedSections()).containsExactlyInAnyOrder("T-DEAL", "T-NONE");
    // 팝업·로딩 화면 자리
    assertThat(
            reasons(
                new Body(
                    List.of(
                        new Banner("B9", "POPUP", "IMG-1", "새 단장", "NONE", null, 0, null, null)),
                    List.of(),
                    null)))
        .isEmpty();
  }

  /** 0.10.0: 넘김 간격 2~30, HOME_INLINE 은 G- 묶음 필수, 홈 순서는 있는 묶음·HOME 테마만·중복 없음·묶음은 빠짐없이. */
  @Test
  void homeOrderAndInterval() {
    Banner inline =
        new Banner("B-IN", "HOME_INLINE", "IMG-1", "아침", "NONE", null, 0, null, null, "G-MID");
    Banner noGroup =
        new Banner("B-IN2", "HOME_INLINE", "IMG-1", "x", "NONE", null, 0, null, null, null);
    Banner topWithGroup =
        new Banner("B-TOP", "HOME_TOP", "IMG-1", "x", "NONE", null, 0, null, null, "G-X");
    Banner orphan =
        new Banner("B-OR", "HOME_INLINE", "IMG-1", "x", "NONE", null, 0, null, null, "G-LOST");
    Theme a = theme("T-A", "A", List.of("P1"));
    Theme b = theme("T-B", "B", List.of("P2"));
    LayoutRules.Tab tab = new LayoutRules.Tab("X-B", "탭", List.of("T-B"), 0, null, null, false);
    Body ok = new Body(List.of(inline), List.of(a), List.of(), null, List.of("G-MID", "T-A"), 5);
    assertThat(reasons(ok)).isEmpty();
    Body bad =
        new Body(
            List.of(inline, noGroup, topWithGroup, orphan),
            List.of(a, b),
            List.of(tab),
            null,
            List.of("G-MID", "T-A", "T-A", "T-B", "G-NONE"),
            1);
    assertThat(reasons(bad))
        .containsExactlyInAnyOrder(
            "INVALID_GROUP",
            "INVALID_GROUP",
            "INVALID_INTERVAL",
            "DUPLICATE_ID",
            "UNKNOWN_SECTION",
            "UNKNOWN_SECTION",
            "UNPLACED_GROUP");
  }

  @org.junit.jupiter.api.Test
  void timerStyleOnlyWithTimerDefaultLabel() {
    assertThat(
            new Theme("T-A", "THEME", "a", null, List.of("P1"), 0, null, T0, true, null, null)
                .timerStyle())
        .isEqualTo("LABEL");
    assertThat(
            new Theme("T-B", "THEME", "b", null, List.of("P1"), 0, null, T0, true, null, "BAND")
                .timerStyle())
        .isEqualTo("BAND");
    assertThat(
            new Theme("T-C", "THEME", "c", null, List.of("P1"), 0, null, null, false, null, "BAND")
                .timerStyle())
        .isNull();
  }

  @Test
  void careCardsChecked() {
    var ok =
        new LayoutRules.CareCards(
            "우리동네 PMart, 이렇게 관리해요",
            List.of(new LayoutRules.CareCard("C-QC", "IMG-1", "사전 품질 검수", "입고 때\n살펴봐요", 0)));
    assertThat(reasons(new Body(List.of(), List.of(), List.of(), null, List.of(), null, ok)))
        .isEmpty();
    var bad =
        new LayoutRules.CareCards(
            "",
            List.of(
                new LayoutRules.CareCard("X1", "IMG-9", "", "최저가 보장", 0),
                new LayoutRules.CareCard("C-A", "IMG-1", "온도", "x".repeat(81), 1),
                new LayoutRules.CareCard("C-A", "IMG-1", "온도", "보관", 2)));
    assertThat(reasons(new Body(List.of(), List.of(), List.of(), null, List.of(), null, bad)))
        .contains(
            "INVALID_TITLE",
            "INVALID_CARD_ID",
            "IMAGE_NOT_FOUND",
            "PROMOTION_NOT_ALLOWED",
            "INVALID_BODY",
            "DUPLICATE_ID");
    var empty = new LayoutRules.CareCards("제목", List.of());
    assertThat(reasons(new Body(List.of(), List.of(), List.of(), null, List.of(), null, empty)))
        .contains("INVALID_CARD_COUNT");
  }
}
