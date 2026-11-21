package com.pmart.storefront.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.storefront.api.generated.StorefrontApi;
import com.pmart.storefront.api.generated.model.DisplayItem;
import com.pmart.storefront.api.generated.model.HomeLayout;
import com.pmart.storefront.api.generated.model.ListBanners200Response;
import com.pmart.storefront.api.generated.model.ListSectionItems200Response;
import com.pmart.storefront.api.generated.model.ListSectionItems200ResponseAsOf;
import com.pmart.storefront.api.generated.model.ListSectionItems200ResponsePage;
import com.pmart.storefront.api.generated.model.Section;
import com.pmart.storefront.api.generated.model.TabKey;
import com.pmart.storefront.application.StorefrontProperties;
import com.pmart.storefront.application.StorefrontQueries;
import com.pmart.storefront.domain.DefaultLayout;
import com.pmart.storefront.infrastructure.DisplayStore;
import com.pmart.storefront.infrastructure.DisplayStore.Item;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 StorefrontApi — 기본 진열(layoutVersion 0). HOME 탭 = "전체 상품" 섹션 하나. **랭킹·신상품 탭(2026-11-20
 * ADR-0075)** — 설정(정책값) {@code pmart.storefront.tabs.*} 이 있을 때만 tabs 에 있고, 없으면 그 탭 요청은 404. 랭킹 = 최근
 * 판매(순위 신호) 많은 순 1위부터 rankingSize 까지(badge RANK·rank), 신상품 = 처음 게시가 newWindowDays 안(badge NEW). 다른
 * 영역에서도 신규 기간 안 상품은 badge NEW. mfcId 를 주면 그 센터 미취급 상품은 빼고 carried=true(0.2.0, BR-49), 없으면 null. 배너는
 * 편성(BO-05) 전이라 비어 있다.
 */
@RestController
public class StorefrontController implements StorefrontApi {

  private final StorefrontQueries queries;
  private final com.pmart.storefront.application.LayoutService layouts;
  private final java.time.Clock clock;

  public StorefrontController(
      StorefrontQueries queries,
      com.pmart.storefront.application.LayoutService layouts,
      java.time.Clock clock) {
    this.queries = queries;
    this.layouts = layouts;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<HomeLayout> getHome(@Nullable String mfcId, @Nullable TabKey tab) {
    return ResponseEntity.ok(layout(tab == null ? TabKey.HOME : tab, mfcId));
  }

  /** MD 탭(0.9.0) — 기간 안 탭의 테마 영역(넣은 순서). 없거나 기간 밖이면 404. */
  @Override
  public ResponseEntity<com.pmart.storefront.api.generated.model.CustomTabLayout> getCustomTab(
      String tabKey, @Nullable String mfcId) {
    com.pmart.storefront.domain.LayoutRules.Tab tab =
        activeTabs().stream()
            .filter(x -> x.tabKey().equals(tabKey))
            .findFirst()
            .orElseThrow(() -> PmartException.notFound("TAB_NOT_FOUND", "탭이 없습니다: " + tabKey));
    java.util.Map<String, com.pmart.storefront.domain.LayoutRules.Theme> byId =
        new java.util.HashMap<>();
    activeThemes().forEach(x -> byId.put(x.sectionId(), x));
    List<Section> sections = new ArrayList<>();
    for (String id : tab.sectionIds()) {
      var theme = byId.get(id);
      if (theme != null) {
        Section s = themeSection(theme, mfcId);
        if (s != null) {
          sections.add(s);
        }
      }
    }
    return ResponseEntity.ok(
        new com.pmart.storefront.api.generated.model.CustomTabLayout()
            .layoutVersion(layouts.currentVersion())
            .tab(customTab(tab))
            .sections(sections));
  }

  @Override
  public ResponseEntity<HomeLayout> getTab(TabKey tabKey, @Nullable String mfcId) {
    return ResponseEntity.ok(layout(tabKey, mfcId));
  }

  @Override
  public ResponseEntity<ListSectionItems200Response> listSectionItems(
      String sectionId,
      @Nullable String mfcId,
      @Nullable Integer layoutVersion,
      Integer size,
      @Nullable String cursor) {
    int current = layouts.currentVersion();
    if (layoutVersion != null && layoutVersion != current) {
      throw new PmartException(
          "LAYOUT_VERSION_CHANGED",
          HttpStatus.CONFLICT,
          "홈 구성이 바뀌었습니다. 홈을 다시 조회하세요.",
          false,
          List.of());
    }
    if (sectionId.startsWith("T-")) {
      return ResponseEntity.ok(themeItems(sectionId, mfcId, size, cursor));
    }
    DisplayStore.Kind kind = kindOf(sectionId);
    if (layoutVersion != null && layoutVersion != current) {
      throw new PmartException(
          "LAYOUT_VERSION_CHANGED",
          HttpStatus.CONFLICT,
          "홈 구성이 바뀌었습니다. 홈을 다시 조회하세요.",
          false,
          List.of());
    }
    StorefrontQueries.Page page = page(kind, mfcId, cursor, size);
    boolean filtered = mfcId != null && !mfcId.isBlank();
    Instant newSince = queries.newSince();
    List<DisplayItem> items = new ArrayList<>();
    for (int n = 0; n < page.items().size(); n++) {
      items.add(item(page.items().get(n), filtered, kind, page.offset() + n + 1, newSince));
    }
    return ResponseEntity.ok(
        new ListSectionItems200Response()
            .sectionId(sectionId)
            .items(items)
            .page(
                new ListSectionItems200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size()))
            .asOf(asOf(page.asOf())));
  }

  @Override
  public ResponseEntity<ListBanners200Response> listBanners(@Nullable String placement) {
    return ResponseEntity.ok(
        new ListBanners200Response()
            .layoutVersion(layouts.currentVersion())
            .items(
                activeBanners().stream()
                    .filter(
                        b ->
                            placement == null
                                ? com.pmart.storefront.domain.LayoutRules.HOME_PLACEMENTS.contains(
                                    b.placement())
                                : placement.equals(b.placement()))
                    .map(StorefrontController::banner)
                    .toList()));
  }

  /** 배너 이미지 본문(0.7.0) — 게시된 진열에 쓰인 것만(운영자는 초안 것도). 이미지 ID 마다 바뀌지 않는다. */
  @Override
  public ResponseEntity<org.springframework.core.io.Resource> getBannerImage(String imageId) {
    var img = layouts.image(imageId);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.parseMediaType(img.row().contentType()))
        .contentLength(img.row().sizeBytes())
        .eTag("\"" + img.row().sha256() + "\"")
        .cacheControl(
            org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365))
                .cachePublic()
                .immutable())
        .body(new org.springframework.core.io.InputStreamResource(img.body()));
  }

  /** 지금 버전의 기간 안 배너(자리·순서). */
  private List<com.pmart.storefront.domain.LayoutRules.Banner> activeBanners() {
    Instant now = clock.instant();
    return layouts.currentBody().map(b -> b.banners()).orElse(List.of()).stream()
        .filter(b -> com.pmart.storefront.domain.LayoutRules.active(b.startsAt(), b.endsAt(), now))
        .sorted(
            java.util.Comparator.comparing(
                    (com.pmart.storefront.domain.LayoutRules.Banner b) ->
                        com.pmart.storefront.domain.LayoutRules.PLACEMENTS.indexOf(b.placement()))
                .thenComparingInt(com.pmart.storefront.domain.LayoutRules.Banner::sortOrder))
        .toList();
  }

  /** HOME 에 보일 기간 안 테마 — MD 탭에 넣은 테마는 뺀다(0.9.0). */
  private List<com.pmart.storefront.domain.LayoutRules.Theme> homeThemes() {
    java.util.Set<String> tabbed =
        layouts
            .currentBody()
            .map(com.pmart.storefront.domain.LayoutRules.Body::tabbedSections)
            .orElse(java.util.Set.of());
    return activeThemes().stream().filter(t -> !tabbed.contains(t.sectionId())).toList();
  }

  /** 지금 버전의 기간 안 MD 탭(순서, 0.9.0). */
  private List<com.pmart.storefront.domain.LayoutRules.Tab> activeTabs() {
    Instant now = clock.instant();
    return layouts.currentBody().map(b -> b.tabs()).orElse(List.of()).stream()
        .filter(x -> com.pmart.storefront.domain.LayoutRules.active(x.startsAt(), x.endsAt(), now))
        .sorted(
            java.util.Comparator.comparingInt(
                com.pmart.storefront.domain.LayoutRules.Tab::sortOrder))
        .toList();
  }

  private static com.pmart.storefront.api.generated.model.CustomTab customTab(
      com.pmart.storefront.domain.LayoutRules.Tab x) {
    return new com.pmart.storefront.api.generated.model.CustomTab()
        .tabKey(x.tabKey())
        .title(x.title())
        .endsAt(x.endsAt() == null ? null : x.endsAt().atOffset(java.time.ZoneOffset.UTC))
        .showTimer(x.showTimer());
  }

  /** 편성 테마 → 홈 영역(첫 묶음). 자동 랭킹은 랭킹이 꺼져 있으면 null(영역 생략). */
  private @Nullable Section themeSection(
      com.pmart.storefront.domain.LayoutRules.Theme t, @Nullable String mfcId) {
    ThemePage page;
    if (t.auto()) {
      if (!rankingOn()) {
        return null;
      }
      page = autoPage(t, mfcId);
    } else {
      page = themePage(t, mfcId, DefaultLayout.FIRST_PAGE, null);
    }
    return new Section()
        .sectionId(t.sectionId())
        .kind(t.auto() ? Section.KindEnum.RANKING : Section.KindEnum.fromValue(t.kind()))
        .title(t.title())
        .subtitle(t.subtitle())
        .state(Section.StateEnum.OK)
        .items(page.items())
        .nextCursor(page.next())
        .viewAllRef("/api/v1/storefront/sections/" + t.sectionId() + "/items")
        .endsAt(t.endsAt() == null ? null : t.endsAt().atOffset(java.time.ZoneOffset.UTC))
        .showTimer(t.showTimer())
        .asOf(asOf(page.asOf()));
  }

  /** 자동 랭킹 테마(RANKING_AUTO) — 랭킹 탭과 같은 순위 상위 autoSize 개, 한 번에(다음 묶음 없음). */
  private ThemePage autoPage(
      com.pmart.storefront.domain.LayoutRules.Theme t, @Nullable String mfcId) {
    int n = t.autoSize() == null ? 10 : t.autoSize();
    StorefrontQueries.Page page = page(DisplayStore.Kind.RANKING, mfcId, null, n);
    boolean filtered = mfcId != null && !mfcId.isBlank();
    Instant newSince = queries.newSince();
    List<DisplayItem> items = new ArrayList<>();
    for (int i = 0; i < page.items().size(); i++) {
      items.add(item(page.items().get(i), filtered, DisplayStore.Kind.RANKING, i + 1, newSince));
    }
    return new ThemePage(items, null, page.asOf());
  }

  /** 지금 버전의 기간 안 테마(순서). */
  private List<com.pmart.storefront.domain.LayoutRules.Theme> activeThemes() {
    Instant now = clock.instant();
    return layouts.currentBody().map(b -> b.themes()).orElse(List.of()).stream()
        .filter(t -> com.pmart.storefront.domain.LayoutRules.active(t.startsAt(), t.endsAt(), now))
        .sorted(
            java.util.Comparator.comparingInt(
                com.pmart.storefront.domain.LayoutRules.Theme::sortOrder))
        .toList();
  }

  private static com.pmart.storefront.api.generated.model.Banner banner(
      com.pmart.storefront.domain.LayoutRules.Banner b) {
    return new com.pmart.storefront.api.generated.model.Banner()
        .bannerId(b.bannerId())
        .placement(b.placement())
        .imageUrl(URI.create("/api/v1/storefront/banner-images/" + b.imageId()))
        .altText(b.altText())
        .linkType(
            com.pmart.storefront.api.generated.model.Banner.LinkTypeEnum.fromValue(b.linkType()))
        .linkRef(b.linkRef())
        .sortOrder(b.sortOrder());
  }

  /** 테마 영역 한 쪽(넣은 순서, 진열 가능·취급만). */
  private ListSectionItems200Response themeItems(
      String sectionId, @Nullable String mfcId, int size, @Nullable String cursor) {
    com.pmart.storefront.domain.LayoutRules.Theme theme =
        activeThemes().stream()
            .filter(t -> t.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow(
                () -> PmartException.notFound("SECTION_NOT_FOUND", "진열 영역이 없습니다: " + sectionId));
    ThemePage page =
        theme.auto()
            ? (cursor == null && rankingOn()
                ? autoPage(theme, mfcId)
                : new ThemePage(List.of(), null, clock.instant()))
            : themePage(theme, mfcId, size, cursor);
    return new ListSectionItems200Response()
        .sectionId(sectionId)
        .items(page.items())
        .page(
            new ListSectionItems200ResponsePage().nextCursor(page.next()).size(page.items().size()))
        .asOf(asOf(page.asOf()));
  }

  private record ThemePage(List<DisplayItem> items, @Nullable String next, Instant asOf) {}

  private ThemePage themePage(
      com.pmart.storefront.domain.LayoutRules.Theme theme,
      @Nullable String mfcId,
      int size,
      @Nullable String cursor) {
    int offset;
    try {
      offset = DefaultLayout.offset(cursor);
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다. 처음부터 다시 조회하세요.");
    }
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId.strip();
    List<Item> all = queries.themeItems(center, theme.productIds());
    List<Item> slice =
        all.subList(Math.min(offset, all.size()), Math.min(offset + size, all.size()));
    Instant newSince = queries.newSince();
    List<DisplayItem> items = new ArrayList<>();
    for (int n = 0; n < slice.size(); n++) {
      items.add(
          item(slice.get(n), center != null, DisplayStore.Kind.LATEST, offset + n + 1, newSince));
    }
    String next = offset + size < all.size() ? DefaultLayout.cursor(offset + size) : null;
    return new ThemePage(items, next, clock.instant());
  }

  /** 켜진 탭(HOME 은 늘, RANKING·NEW 는 설정이 있을 때). */
  private List<TabKey> tabs() {
    List<TabKey> tabs = new ArrayList<>(List.of(TabKey.HOME));
    if (rankingOn()) {
      tabs.add(TabKey.RANKING);
    }
    if (newOn()) {
      tabs.add(TabKey.NEW);
    }
    return tabs;
  }

  private boolean rankingOn() {
    StorefrontProperties.Tabs t = queries.tabs();
    return t != null && t.rankingTab() && queries.rankingWindowDays() != null;
  }

  private boolean newOn() {
    StorefrontProperties.Tabs t = queries.tabs();
    return t != null && t.newTab();
  }

  private HomeLayout layout(TabKey tab, @Nullable String mfcId) {
    List<TabKey> tabs = tabs();
    if (!tabs.contains(tab)) {
      throw tabNotConfigured(tab);
    }
    Section section =
        switch (tab) {
          case RANKING ->
              section(
                  DisplayStore.Kind.RANKING,
                  DefaultLayout.RANKING_SECTION_ID,
                  Section.KindEnum.RANKING,
                  "랭킹",
                  "최근 " + queries.rankingWindowDays() + "일 많이 팔린 상품",
                  "/api/v1/search/products?sort=POPULAR",
                  mfcId);
          case NEW ->
              section(
                  DisplayStore.Kind.NEW,
                  DefaultLayout.NEW_SECTION_ID,
                  Section.KindEnum.NEW,
                  "신상품",
                  "최근 "
                      + java.util.Objects.requireNonNull(queries.tabs()).newWindowDays()
                      + "일 새로 나온 상품",
                  DefaultLayout.VIEW_ALL_REF,
                  mfcId);
          case HOME ->
              section(
                  DisplayStore.Kind.LATEST,
                  DefaultLayout.SECTION_ID,
                  Section.KindEnum.THEME,
                  DefaultLayout.TITLE,
                  DefaultLayout.SUBTITLE,
                  DefaultLayout.VIEW_ALL_REF,
                  mfcId);
        };
    List<Section> sections = new ArrayList<>();
    List<com.pmart.storefront.api.generated.model.Banner> banners = List.of();
    if (tab == TabKey.HOME) {
      // 편성(BO-05, ADR-0075): 기간 안 배너 + 테마 영역(순서) + 마지막 "전체 상품"
      banners =
          activeBanners().stream()
              .filter(
                  b ->
                      com.pmart.storefront.domain.LayoutRules.HOME_PLACEMENTS.contains(
                          b.placement()))
              .map(StorefrontController::banner)
              .toList();
      for (com.pmart.storefront.domain.LayoutRules.Theme t : homeThemes()) {
        Section s = themeSection(t, mfcId);
        if (s != null) {
          sections.add(s);
        }
      }
    }
    sections.add(section);
    return new HomeLayout()
        .layoutVersion(layouts.currentVersion())
        .tab(tab)
        .tabs(tabs)
        .banners(banners)
        .sections(sections)
        .customTabs(activeTabs().stream().map(StorefrontController::customTab).toList());
  }

  private Section section(
      DisplayStore.Kind kind,
      String sectionId,
      Section.KindEnum sectionKind,
      String title,
      String subtitle,
      String viewAll,
      @Nullable String mfcId) {
    StorefrontQueries.Page page = page(kind, mfcId, null, DefaultLayout.FIRST_PAGE);
    boolean filtered = mfcId != null && !mfcId.isBlank();
    Instant newSince = queries.newSince();
    List<DisplayItem> items = new ArrayList<>();
    for (int n = 0; n < page.items().size(); n++) {
      items.add(item(page.items().get(n), filtered, kind, page.offset() + n + 1, newSince));
    }
    return new Section()
        .sectionId(sectionId)
        .kind(sectionKind)
        .title(title)
        .subtitle(subtitle)
        .state(Section.StateEnum.OK)
        .items(items)
        .nextCursor(page.nextCursor())
        .viewAllRef(viewAll)
        .asOf(asOf(page.asOf()));
  }

  private DisplayStore.Kind kindOf(String sectionId) {
    if (DefaultLayout.SECTION_ID.equals(sectionId)) {
      return DisplayStore.Kind.LATEST;
    }
    if (DefaultLayout.RANKING_SECTION_ID.equals(sectionId) && rankingOn()) {
      return DisplayStore.Kind.RANKING;
    }
    if (DefaultLayout.NEW_SECTION_ID.equals(sectionId) && newOn()) {
      return DisplayStore.Kind.NEW;
    }
    throw PmartException.notFound("SECTION_NOT_FOUND", "진열 영역이 없습니다: " + sectionId);
  }

  private StorefrontQueries.Page page(
      DisplayStore.Kind kind, @Nullable String mfcId, @Nullable String cursor, int size) {
    try {
      return kind == DisplayStore.Kind.LATEST
          ? queries.sectionPage(mfcId, cursor, size)
          : queries.tabPage(kind, mfcId, cursor, size);
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다. 처음부터 다시 조회하세요.");
    }
  }

  /**
   * filtered = mfcId 로 미취급을 뺐다 → 남은 것은 취급(true). 아니면 판정 안 함(null). 랭킹 영역은 badge RANK·rank, 그 밖은 신규
   * 기간 안이면 NEW.
   */
  private static DisplayItem item(
      Item i, boolean filtered, DisplayStore.Kind kind, int position, @Nullable Instant newSince) {
    DisplayItem.BadgeEnum badge =
        kind == DisplayStore.Kind.RANKING
            ? DisplayItem.BadgeEnum.RANK
            : newSince != null && i.publishedAt() != null && !i.publishedAt().isBefore(newSince)
                ? DisplayItem.BadgeEnum.NEW
                : DisplayItem.BadgeEnum.NONE;
    return item(i, filtered).badge(badge).rank(kind == DisplayStore.Kind.RANKING ? position : null);
  }

  /** filtered = mfcId 로 미취급을 뺐다 → 남은 것은 취급(true). 아니면 판정 안 함(null). */
  private static DisplayItem item(Item i, boolean filtered) {
    return new DisplayItem()
        .productId(i.productId())
        .name(i.name())
        .imageUrl(i.imageUrl() == null ? null : URI.create(i.imageUrl()))
        .representativeSku(i.representativeSku())
        .basePrice(i.basePrice() == null ? null : Math.toIntExact(i.basePrice()))
        .priceVersion(i.priceVersion())
        // 기간 할인(0.8.0 ADR-0082 4단계) — 가격 사본과 같은 버전·끝 전일 때만(DisplayStore)
        .salePrice(i.salePrice() == null ? null : Math.toIntExact(i.salePrice()))
        .discountRatePercent(i.discountRatePercent())
        .discountName(i.discountName())
        .discountEndsAt(
            i.discountEndsAt() == null
                ? null
                : java.time.OffsetDateTime.ofInstant(i.discountEndsAt(), java.time.ZoneOffset.UTC))
        .saleState(DisplayItem.SaleStateEnum.fromValue(i.saleState()))
        .carried(filtered ? Boolean.TRUE : null)
        .netContent(netContent(i))
        .unitPrice(unitPrice(i))
        // BR-63(0.4.0) — 공개 리뷰 수·평균(소수 첫째, 리뷰 없으면 null)
        .reviewCount(i.reviewCount())
        .rating(
            i.rating() == null
                ? null
                : java.math.BigDecimal.valueOf(Math.round(i.rating() * 10) / 10.0))
        .badge(DisplayItem.BadgeEnum.NONE);
  }

  private static com.pmart.storefront.api.generated.model.@org.jspecify.annotations.Nullable
      DisplayItemNetContent
      netContent(Item i) {
    if (i.netContentAmount() == null || i.netContentUnit() == null) {
      return null;
    }
    return new com.pmart.storefront.api.generated.model.DisplayItemNetContent()
        .amount(i.netContentAmount())
        .unit(
            com.pmart.storefront.api.generated.model.DisplayItemNetContent.UnitEnum.fromValue(
                i.netContentUnit()));
  }

  /** 단위 가격(BR-19) — 규칙은 domain.UnitPrice. */
  private static com.pmart.storefront.api.generated.model.@org.jspecify.annotations.Nullable
      DisplayItemUnitPrice
      unitPrice(Item i) {
    Long per = com.pmart.storefront.domain.UnitPrice.of(i.basePrice(), i.netContentAmount());
    if (per == null || i.netContentUnit() == null) {
      return null;
    }
    return new com.pmart.storefront.api.generated.model.DisplayItemUnitPrice()
        .basis(com.pmart.storefront.domain.UnitPrice.BASIS)
        .unit(
            com.pmart.storefront.api.generated.model.DisplayItemUnitPrice.UnitEnum.fromValue(
                i.netContentUnit()))
        .price(Math.toIntExact(per));
  }

  private static ListSectionItems200ResponseAsOf asOf(Instant at) {
    return new ListSectionItems200ResponseAsOf()
        .asOf(OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
        .freshness(ListSectionItems200ResponseAsOf.FreshnessEnum.CURRENT);
  }

  private static PmartException tabNotConfigured(TabKey tab) {
    return PmartException.notFound("TAB_NOT_CONFIGURED", "아직 구성되지 않은 탭입니다: " + tab.getValue());
  }
}
