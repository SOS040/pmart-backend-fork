package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.cart.infrastructure.CartStore.CartRow;
import com.pmart.cart.infrastructure.CartStore.ItemRow;
import com.pmart.cart.infrastructure.CatalogClient;
import com.pmart.common.web.PmartException;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 장바구니 조회 화면. 이름·이미지(콘텐츠)·가격(pricing)·판매 상태(product)는 각 정본의 사본이며, 출처마다 따로 실패할 수 있다 — 실패한 출처는 비우고
 * sources 의 freshness 를 UNAVAILABLE 로 알린다(0원·판매중지로 바꾸지 않음). 가용은 장바구니의 담당 MFC 기준 mfc-inventory
 * 사본(MFC 를 모르거나 조회 실패면 UNKNOWN, 0 아님 — CartRules.availability).
 *
 * <p>네 출처는 서로 독립이라 가상 스레드로 동시에 부른다(BR-42, 2026-11-03) — 걸리는 시간이 합이 아니라 가장 느린 하나. traceId·MDC 는
 * context-propagation 으로 넘긴다. 출처마다 시간 제한은 공통 기본값(연결 1초·응답 5초, common-web).
 */
@Service
public class CartView {

  private static final Logger log = LoggerFactory.getLogger(CartView.class);

  /** 표시용 조회 실행기 — 작업마다 가상 스레드, 호출 스레드의 관측(trace)·MDC 를 넘긴다. */
  private static final ExecutorService LOOKUPS =
      ContextExecutorService.wrap(
          Executors.newVirtualThreadPerTaskExecutor(),
          () -> ContextSnapshotFactory.builder().build().captureAll());

  public record Line(
      ItemRow item,
      @Nullable String name,
      @Nullable String imageUrl,
      @Nullable Long unitPrice,
      @Nullable Integer priceVersion,
      String saleState,
      CartRules.Availability availability) {}

  public record Source(String system, boolean available, Instant asOf) {}

  public record View(
      CartRow cart,
      List<Line> lines,
      @Nullable Long selectedAmount,
      Instant expiresAt,
      List<Source> sources) {}

  private final CartStore store;
  private final CatalogClient catalog;
  private final CartProperties props;
  private final Clock clock;

  public CartView(CartStore store, CatalogClient catalog, CartProperties props, Clock clock) {
    this.store = store;
    this.catalog = catalog;
    this.props = props;
    this.clock = clock;
  }

  public View view(CartRow cart) {
    List<ItemRow> items = store.items(cart.cartId());
    List<String> skus = items.stream().map(ItemRow::sku).toList();
    List<String> productIds =
        items.stream().map(ItemRow::productId).filter(p -> p != null).distinct().toList();
    Instant now = clock.instant();
    var fDetails = async("product-service", () -> catalog.skus(skus), now);
    var fCards = async("product-content-service", () -> catalog.cards(productIds), now);
    var fPrices = async("pricing-service", () -> catalog.prices(skus), now);
    // 담당 MFC 를 모르면(쇼핑 주소 미선택) 가용은 불명 — 조회하지 않는다
    String mfcId = cart.mfcId();
    var fStock =
        mfcId == null
            ? null
            : async("mfc-inventory-service", () -> catalog.stock(mfcId, skus), now);
    // sources 는 지금까지와 같은 순서(product → content → pricing → inventory)
    List<Source> sources = new ArrayList<>();
    Map<String, CatalogClient.Sku> details = fDetails.join().take(sources);
    Map<String, CatalogClient.Card> cards = fCards.join().take(sources);
    Map<String, CatalogClient.Price> prices = fPrices.join().take(sources);
    Map<String, CatalogClient.Stock> stock =
        fStock == null ? Map.of() : fStock.join().take(sources);
    boolean pricingOk =
        sources.stream().anyMatch(s -> s.system().equals("pricing-service") && s.available());

    List<Line> lines = new ArrayList<>();
    long amount = 0;
    boolean amountKnown = pricingOk;
    for (ItemRow i : items) {
      CatalogClient.Card card = i.productId() == null ? null : cards.get(i.productId());
      CatalogClient.Price price = prices.get(i.sku());
      CatalogClient.Sku d = details.get(i.sku());
      CatalogClient.Stock s = stock.get(i.sku());
      lines.add(
          new Line(
              i,
              card == null ? null : card.name(),
              card == null ? null : card.imageUrl(),
              price == null ? null : price.amount(),
              price == null ? null : price.version(),
              d == null ? "UNKNOWN" : d.saleState(),
              s == null
                  ? CartRules.Availability.UNKNOWN
                  : CartRules.availability(s.available(), s.handlingEnabled(), i.quantity())));
      if (i.selected()) {
        if (price == null) {
          amountKnown = false;
        } else {
          amount += price.amount() * i.quantity();
        }
      }
    }
    return new View(
        cart,
        lines,
        amountKnown ? amount : null,
        cart.lastChangedAt().plus(props.retention()),
        sources);
  }

  /** 출처 하나 조회. 실패하면 빈 결과 + UNAVAILABLE(화면 전체를 실패시키지 않는다). */
  /** 한 출처의 조회 결과와 그 출처 상태. */
  private record Fetched<T>(Map<String, T> data, Source source) {
    Map<String, T> take(List<Source> sources) {
      sources.add(source);
      return data;
    }
  }

  private <T> CompletableFuture<Fetched<T>> async(
      String system, Supplier<Map<String, T>> call, Instant now) {
    return CompletableFuture.supplyAsync(() -> fetch(system, call, now), LOOKUPS);
  }

  private <T> Fetched<T> fetch(String system, Supplier<Map<String, T>> call, Instant now) {
    try {
      return new Fetched<>(call.get(), new Source(system, true, now));
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("장바구니 표시용 {} 조회 실패 — 불명으로 표시", system);
      return new Fetched<>(Map.of(), new Source(system, false, now));
    }
  }
}
