package com.pmart.review.application;

import com.pmart.review.infrastructure.ReviewStore;
import com.pmart.review.infrastructure.ReviewStore.EligibilityRow;
import com.pmart.review.infrastructure.Upstreams;
import com.pmart.review.infrastructure.Upstreams.ProductCard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 리뷰관리 화면 표시 값(2026-11-20, 계약 0.5.0 — 고객 앱 BR-67, 사용자 "추천대로"). 자격 한 쪽에 붙이는 부가 값이라 어느 것을 못 읽어도 목록은
 * 그대로 내고 그 값만 null 로 둔다(지어내지 않는다).
 *
 * <ul>
 *   <li>주문 시각·센터 ID: 자격 행의 order 사본(V4). V4 전에 만든 행은 이 쪽의 주문마다 order 내부 주문을 한 번 읽어 채운다.
 *   <li>센터 이름: center-master 내부 센터 조회 name 의 10분 사본(실패는 1분 뒤 다시) — order {@code CenterNames} 와 같은
 *       방식.
 *   <li>상품 사진·옵션 라벨: product-content 카드 일괄 조회(한 쪽에 한 번, 100개씩) — 주문 당시 사본이 아니라 지금 대표 사진. 옵션 라벨은 그
 *       카드의 SKU 옵션 이름(없으면 null).
 * </ul>
 */
@Service
public class EligibilityDisplay {

  private static final Logger log = LoggerFactory.getLogger(EligibilityDisplay.class);

  static final Duration CENTER_TTL = Duration.ofMinutes(10);
  static final Duration CENTER_RETRY = Duration.ofMinutes(1);

  /** 한 품목의 표시 값. 모르면 null. */
  public record Display(
      @Nullable Instant orderedAt,
      @Nullable String mfcName,
      @Nullable String imageUrl,
      @Nullable String optionLabel) {}

  private record CenterEntry(@Nullable String name, Instant until) {}

  private final ReviewStore store;
  private final Upstreams upstreams;
  private final Clock clock;
  private final Map<String, CenterEntry> centers = new ConcurrentHashMap<>();

  public EligibilityDisplay(ReviewStore store, Upstreams upstreams, Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.clock = clock;
  }

  /** 자격 행마다(같은 순서) 표시 값. */
  public List<Display> of(List<EligibilityRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, OrderFacts> facts = orderFacts(rows);
    Map<String, ProductCard> cards = cards(rows);
    return rows.stream()
        .map(
            r -> {
              OrderFacts f = facts.getOrDefault(r.orderId(), new OrderFacts(null, null));
              String mfcName = f.mfcId() == null ? null : centerName(f.mfcId());
              ProductCard card = r.productId() == null ? null : cards.get(r.productId());
              return new Display(
                  f.orderedAt(),
                  mfcName,
                  card == null ? null : card.imageUrl(),
                  card == null || r.sku() == null ? null : card.optionLabels().get(r.sku()));
            })
        .toList();
  }

  private record OrderFacts(@Nullable Instant orderedAt, @Nullable String mfcId) {}

  /** 행에 있는 사본, 없으면 주문마다 order 를 한 번 읽어 행을 채운다(실패·없는 주문은 null 로 둔다 — 다음 조회 때 다시). */
  private Map<String, OrderFacts> orderFacts(List<EligibilityRow> rows) {
    Map<String, OrderFacts> out = new HashMap<>();
    Set<String> missing = new LinkedHashSet<>();
    for (EligibilityRow r : rows) {
      if (r.orderedAt() != null || r.mfcId() != null) {
        out.putIfAbsent(r.orderId(), new OrderFacts(r.orderedAt(), r.mfcId()));
      } else {
        missing.add(r.orderId());
      }
    }
    missing.removeAll(out.keySet());
    for (String orderId : missing) {
      try {
        Upstreams.Order o = upstreams.order(orderId);
        if (o == null) {
          continue;
        }
        store.fillOrderFacts(orderId, o.confirmedAt(), o.mfcId());
        out.put(orderId, new OrderFacts(o.confirmedAt(), o.mfcId()));
      } catch (RuntimeException e) {
        log.warn("주문 {} 의 주문 시각·센터를 읽지 못함 — 비워 둠: {}", orderId, e.toString());
      }
    }
    return out;
  }

  private Map<String, ProductCard> cards(List<EligibilityRow> rows) {
    List<String> productIds =
        rows.stream().map(EligibilityRow::productId).filter(Objects::nonNull).distinct().toList();
    try {
      return upstreams.productCards(productIds);
    } catch (RuntimeException e) {
      log.warn("상품 카드를 읽지 못함 — 사진·옵션 비워 둠: {}", e.toString());
      return Map.of();
    }
  }

  /** 센터 이름 10분 사본. 실패하면 이전 사본(없으면 null)을 1분 둔다. */
  @Nullable String centerName(String mfcId) {
    Instant now = clock.instant();
    CenterEntry e = centers.get(mfcId);
    if (e != null && e.until().isAfter(now)) {
      return e.name();
    }
    String name;
    Duration keep;
    try {
      name = upstreams.centerName(mfcId);
      keep = CENTER_TTL;
    } catch (RuntimeException ex) {
      log.warn("센터 {} 이름을 읽지 못함: {}", mfcId, ex.toString());
      name = e == null ? null : e.name();
      keep = CENTER_RETRY;
    }
    centers.put(mfcId, new CenterEntry(name, now.plus(keep)));
    return name;
  }
}
