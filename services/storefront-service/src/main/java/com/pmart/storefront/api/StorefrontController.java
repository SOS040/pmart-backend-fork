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
import com.pmart.storefront.application.StorefrontQueries;
import com.pmart.storefront.domain.DefaultLayout;
import com.pmart.storefront.infrastructure.DisplayStore.Item;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 StorefrontApi — 기본 진열(layoutVersion 0: HOME 탭, "전체 상품" 섹션 하나). 진열 편성·배너 승인 전이라 배너는 비어 있고
 * RANKING·NEW 탭은 아직 없다(404). mfcId 를 주면 그 센터 미취급 상품은 빼고 carried=true(0.2.0, BR-49), 없으면 null.
 */
@RestController
public class StorefrontController implements StorefrontApi {

  private final StorefrontQueries queries;

  public StorefrontController(StorefrontQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<HomeLayout> getHome(@Nullable String mfcId, @Nullable TabKey tab) {
    if (tab != null && tab != TabKey.HOME) {
      throw tabNotConfigured(tab);
    }
    return ResponseEntity.ok(home(mfcId));
  }

  @Override
  public ResponseEntity<HomeLayout> getTab(TabKey tabKey, @Nullable String mfcId) {
    if (tabKey != TabKey.HOME) {
      throw tabNotConfigured(tabKey);
    }
    return ResponseEntity.ok(home(mfcId));
  }

  @Override
  public ResponseEntity<ListSectionItems200Response> listSectionItems(
      String sectionId,
      @Nullable String mfcId,
      @Nullable Integer layoutVersion,
      Integer size,
      @Nullable String cursor) {
    if (!DefaultLayout.SECTION_ID.equals(sectionId)) {
      throw PmartException.notFound("SECTION_NOT_FOUND", "진열 영역이 없습니다: " + sectionId);
    }
    if (layoutVersion != null && layoutVersion != DefaultLayout.VERSION) {
      throw new PmartException(
          "LAYOUT_VERSION_CHANGED",
          HttpStatus.CONFLICT,
          "홈 구성이 바뀌었습니다. 홈을 다시 조회하세요.",
          false,
          List.of());
    }
    StorefrontQueries.Page page = page(mfcId, cursor, size);
    boolean filtered = mfcId != null && !mfcId.isBlank();
    return ResponseEntity.ok(
        new ListSectionItems200Response()
            .sectionId(sectionId)
            .items(page.items().stream().map(i -> item(i, filtered)).toList())
            .page(
                new ListSectionItems200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size()))
            .asOf(asOf(page.asOf())));
  }

  @Override
  public ResponseEntity<ListBanners200Response> listBanners(@Nullable String placement) {
    return ResponseEntity.ok(
        new ListBanners200Response().layoutVersion(DefaultLayout.VERSION).items(List.of()));
  }

  private HomeLayout home(@Nullable String mfcId) {
    StorefrontQueries.Page page = page(mfcId, null, DefaultLayout.FIRST_PAGE);
    boolean filtered = mfcId != null && !mfcId.isBlank();
    Section all =
        new Section()
            .sectionId(DefaultLayout.SECTION_ID)
            .kind(Section.KindEnum.THEME)
            .title(DefaultLayout.TITLE)
            .subtitle(DefaultLayout.SUBTITLE)
            .state(Section.StateEnum.OK)
            .items(page.items().stream().map(i -> item(i, filtered)).toList())
            .nextCursor(page.nextCursor())
            .viewAllRef(DefaultLayout.VIEW_ALL_REF)
            .asOf(asOf(page.asOf()));
    return new HomeLayout()
        .layoutVersion(DefaultLayout.VERSION)
        .tab(TabKey.HOME)
        .tabs(List.of(TabKey.HOME))
        .banners(List.of())
        .sections(List.of(all));
  }

  private StorefrontQueries.Page page(@Nullable String mfcId, @Nullable String cursor, int size) {
    try {
      return queries.sectionPage(mfcId, cursor, size);
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다. 처음부터 다시 조회하세요.");
    }
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
        .saleState(DisplayItem.SaleStateEnum.fromValue(i.saleState()))
        .carried(filtered ? Boolean.TRUE : null)
        .badge(DisplayItem.BadgeEnum.NONE);
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
