package com.pmart.storefront.application;

import com.pmart.storefront.domain.DefaultLayout;
import com.pmart.storefront.infrastructure.DisplayStore;
import com.pmart.storefront.infrastructure.DisplayStore.Item;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** 기본 진열(DefaultLayout) 조회. 가격·판매 상태는 사건 사본이며 asOf 를 함께 준다. */
@Service
public class StorefrontQueries {

  /** offset = 이 쪽 첫 항목의 위치(랭킹 순위 = offset + 순번 + 1). */
  public record Page(List<Item> items, @Nullable String nextCursor, Instant asOf, int offset) {}

  private final DisplayStore store;
  private final Clock clock;
  private final StorefrontProperties props;

  public StorefrontQueries(DisplayStore store, Clock clock, StorefrontProperties props) {
    this.store = store;
    this.clock = clock;
    this.props = props;
  }

  public Page sectionPage(@Nullable String cursor, int size) {
    return sectionPage(null, cursor, size);
  }

  /** mfcId 가 있으면 그 센터 미취급 상품은 뺀다(BR-49). 비면 빈 쪽·다음 커서 없음. */
  public Page sectionPage(@Nullable String mfcId, @Nullable String cursor, int size) {
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId.strip();
    int offset = DefaultLayout.offset(cursor);
    List<Item> items = store.latest(center, offset, size);
    String next =
        items.size() == size && offset + size < store.countDisplayable(center)
            ? DefaultLayout.cursor(offset + size)
            : null;
    Instant asOf = store.lastUpdated();
    return new Page(items, next, asOf == null ? clock.instant() : asOf, offset);
  }

  /**
   * 랭킹·신상품 영역 한 쪽(2026-11-20 ADR-0075). 랭킹은 1위부터 rankingSize 까지만(그 너머 커서 없음), 신상품은 처음 게시가
   * newWindowDays 안인 것.
   */
  public Page tabPage(
      DisplayStore.Kind kind, @Nullable String mfcId, @Nullable String cursor, int size) {
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId.strip();
    int offset = DefaultLayout.offset(cursor);
    StorefrontProperties.Tabs tabs = props.tabs();
    Instant since = null;
    int limit = size;
    int total;
    if (kind == DisplayStore.Kind.RANKING) {
      int cap = java.util.Objects.requireNonNull(tabs).rankingSize();
      limit = Math.max(0, Math.min(size, cap - offset));
      total = Math.min(cap, store.count(center, kind, null));
    } else {
      since =
          clock
              .instant()
              .minus(
                  java.time.Duration.ofDays(
                      java.util.Objects.requireNonNull(tabs).newWindowDays()));
      total = store.count(center, kind, since);
    }
    List<Item> items = limit == 0 ? List.of() : store.items(center, kind, since, offset, limit);
    String next =
        items.size() == limit && offset + limit < total
            ? DefaultLayout.cursor(offset + limit)
            : null;
    Instant asOf = store.lastUpdated();
    return new Page(items, next, asOf == null ? clock.instant() : asOf, offset);
  }

  /** 신상품 배지를 붙일 기준 시각(신규 기간 설정이 없으면 null — 배지 없음). */
  public @Nullable Instant newSince() {
    StorefrontProperties.Tabs tabs = props.tabs();
    return tabs != null && tabs.newTab()
        ? clock.instant().minus(java.time.Duration.ofDays(tabs.newWindowDays()))
        : null;
  }

  /** 순위 집계기간(일) — 순위 신호 설정(ADR-0072). 없으면 null(랭킹 탭 없음). */
  public @Nullable Integer rankingWindowDays() {
    StorefrontProperties.Ranking r = props.ranking();
    return r != null && r.complete() ? r.salesWindowDays() : null;
  }

  public StorefrontProperties.@Nullable Tabs tabs() {
    return props.tabs();
  }
}
