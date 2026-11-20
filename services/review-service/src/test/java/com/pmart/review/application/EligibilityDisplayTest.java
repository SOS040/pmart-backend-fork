package com.pmart.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.review.infrastructure.ReviewStore;
import com.pmart.review.infrastructure.ReviewStore.EligibilityRow;
import com.pmart.review.infrastructure.Upstreams;
import com.pmart.review.infrastructure.Upstreams.ProductCard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 리뷰관리 표시 값(0.5.0 BR-67): 주문 사본·센터 이름 10분 사본·지금 상품 사진·옵션 라벨, 못 읽으면 그 값만 null. */
class EligibilityDisplayTest {

  static final Instant ORDERED = Instant.parse("2026-11-10T01:00:00Z");
  static final Instant DELIVERED = Instant.parse("2026-11-10T03:00:00Z");

  /** 기록하는 가짜 order·center·content. */
  static class FakeUpstreams extends Upstreams {
    final Map<String, Order> orders = new HashMap<>();
    final Map<String, String> centers = new HashMap<>();
    final Map<String, ProductCard> cards = new HashMap<>();
    final List<String> orderCalls = new ArrayList<>();
    int centerCalls;
    boolean centerDown;
    boolean contentDown;

    FakeUpstreams() {
      super(
          RestClient.builder(),
          new ReviewProperties(null, null, null, null),
          JsonMapper.builder().build());
    }

    @Override
    public @Nullable Order order(String orderId) {
      orderCalls.add(orderId);
      return orders.get(orderId);
    }

    @Override
    public @Nullable String centerName(String mfcId) {
      centerCalls++;
      if (centerDown) {
        throw new IllegalStateException("center down");
      }
      return centers.get(mfcId);
    }

    @Override
    public Map<String, ProductCard> productCards(Collection<String> productIds) {
      if (contentDown) {
        throw new IllegalStateException("content down");
      }
      Map<String, ProductCard> out = new HashMap<>();
      productIds.forEach(
          id -> {
            if (cards.containsKey(id)) {
              out.put(id, cards.get(id));
            }
          });
      return out;
    }
  }

  static class FakeStore extends ReviewStore {
    final List<String> filled = new ArrayList<>();

    FakeStore() {
      super(null);
    }

    @Override
    public void fillOrderFacts(
        String orderId, @Nullable Instant orderedAt, @Nullable String mfcId) {
      filled.add(orderId + "|" + orderedAt + "|" + mfcId);
    }
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-20T00:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  final FakeUpstreams up = new FakeUpstreams();
  final FakeStore store = new FakeStore();
  final MutableClock clock = new MutableClock();
  final EligibilityDisplay display = new EligibilityDisplay(store, up, clock);

  static EligibilityRow row(
      String orderId,
      String sku,
      @Nullable String productId,
      @Nullable Instant at,
      @Nullable String mfcId) {
    return new EligibilityRow(
        orderId + "-" + sku,
        orderId,
        "ACC-1",
        sku,
        productId,
        "사과",
        1,
        DELIVERED,
        "DLV-1",
        at,
        mfcId);
  }

  @Test
  void storedOrderFactsCenterNameAndCurrentProductImage() {
    up.centers.put("MFC-1", "강남센터");
    up.cards.put(
        "PRD-1", new ProductCard("/api/v1/content-images/IMG-1", Map.of("E-00000001", "1팩(6입)")));

    var shown =
        display.of(
            List.of(
                row("ORD-1", "E-00000001", "PRD-1", ORDERED, "MFC-1"),
                row("ORD-1", "E-00000002", "PRD-1", ORDERED, "MFC-1")));

    assertThat(shown.get(0))
        .isEqualTo(
            new EligibilityDisplay.Display(
                ORDERED, "강남센터", "/api/v1/content-images/IMG-1", "1팩(6입)"));
    assertThat(shown.get(1).optionLabel()).as("콘텐츠에 그 SKU 옵션 이름이 없으면 null").isNull();
    assertThat(shown.get(1).imageUrl()).isEqualTo("/api/v1/content-images/IMG-1");
    assertThat(up.orderCalls).as("행에 사본이 있으면 order 를 부르지 않는다").isEmpty();
    assertThat(up.centerCalls).as("센터 이름은 사본 — 한 번만").isEqualTo(1);
  }

  @Test
  void rowsBeforeV4ReadOrderOncePerOrderAndFillTheRows() {
    up.orders.put("ORD-2", new Upstreams.Order("ORD-2", "ACC-1", ORDERED, "MFC-2", List.of()));
    up.centers.put("MFC-2", "송파센터");

    var shown =
        display.of(
            List.of(
                row("ORD-2", "E-00000001", "PRD-9", null, null),
                row("ORD-2", "E-00000002", "PRD-9", null, null),
                row("ORD-GONE", "E-00000003", null, null, null)));

    assertThat(up.orderCalls).containsExactly("ORD-2", "ORD-GONE");
    assertThat(store.filled).containsExactly("ORD-2|" + ORDERED + "|MFC-2");
    assertThat(shown.get(0).orderedAt()).isEqualTo(ORDERED);
    assertThat(shown.get(1).mfcName()).isEqualTo("송파센터");
    assertThat(shown.get(0).imageUrl()).as("게시 안 된 상품은 사진 없음").isNull();
    assertThat(shown.get(2))
        .as("order 에 없는 주문·상품 ID 없는 행은 모두 null")
        .isEqualTo(new EligibilityDisplay.Display(null, null, null, null));
  }

  @Test
  void upstreamFailuresLeaveOnlyThoseValuesNull() {
    up.contentDown = true;
    up.centerDown = true;
    var shown = display.of(List.of(row("ORD-3", "E-00000001", "PRD-1", ORDERED, "MFC-3")));
    assertThat(shown.get(0)).isEqualTo(new EligibilityDisplay.Display(ORDERED, null, null, null));

    // order 장애도 목록을 막지 않는다
    FakeUpstreams broken =
        new FakeUpstreams() {
          @Override
          public @Nullable Order order(String orderId) {
            throw new IllegalStateException("order down");
          }
        };
    var d = new EligibilityDisplay(store, broken, clock);
    assertThat(d.of(List.of(row("ORD-4", "E-00000001", null, null, null))).get(0).orderedAt())
        .isNull();
    assertThat(store.filled).isEmpty();
  }

  @Test
  void centerNameCacheKeepsLastNameOnFailureAndRetriesAfterAMinute() {
    up.centers.put("MFC-1", "강남센터");
    assertThat(display.centerName("MFC-1")).isEqualTo("강남센터");

    clock.now = clock.now.plus(EligibilityDisplay.CENTER_TTL).plusSeconds(1);
    up.centerDown = true;
    assertThat(display.centerName("MFC-1")).as("실패면 이전 사본").isEqualTo("강남센터");
    assertThat(up.centerCalls).isEqualTo(2);

    clock.now = clock.now.plus(Duration.ofSeconds(30));
    display.centerName("MFC-1");
    assertThat(up.centerCalls).as("실패 뒤 1분 안에는 다시 부르지 않는다").isEqualTo(2);

    clock.now = clock.now.plus(EligibilityDisplay.CENTER_RETRY);
    up.centerDown = false;
    up.centers.put("MFC-1", "강남MFC");
    assertThat(display.centerName("MFC-1")).isEqualTo("강남MFC");
  }
}
