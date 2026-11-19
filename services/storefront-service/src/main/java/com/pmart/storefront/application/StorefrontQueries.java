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

  public record Page(List<Item> items, @Nullable String nextCursor, Instant asOf) {}

  private final DisplayStore store;
  private final Clock clock;

  public StorefrontQueries(DisplayStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
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
    return new Page(items, next, asOf == null ? clock.instant() : asOf);
  }
}
