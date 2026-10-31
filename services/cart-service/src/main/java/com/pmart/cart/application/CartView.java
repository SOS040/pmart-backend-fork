package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.cart.infrastructure.CartStore.CartRow;
import com.pmart.cart.infrastructure.CartStore.ItemRow;
import com.pmart.cart.infrastructure.CatalogClient;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 장바구니 조회 화면. 이름·이미지(콘텐츠)·가격(pricing)·판매 상태(product)는 각 정본의 사본이며, 출처마다 따로 실패할 수 있다 — 실패한 출처는 비우고
 * sources 의 freshness 를 UNAVAILABLE 로 알린다(0원·판매중지로 바꾸지 않음). 가용은 장바구니의 담당 MFC 기준 mfc-inventory
 * 사본(MFC 를 모르거나 조회 실패면 UNKNOWN, 0 아님 — CartRules.availability).
 */
@Service
public class CartView {

  private static final Logger log = LoggerFactory.getLogger(CartView.class);

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
    List<Source> sources = new ArrayList<>();
    Map<String, CatalogClient.Sku> details =
        fetch("product-service", () -> catalog.skus(skus), sources, now);
    Map<String, CatalogClient.Card> cards =
        fetch("product-content-service", () -> catalog.cards(productIds), sources, now);
    Map<String, CatalogClient.Price> prices =
        fetch("pricing-service", () -> catalog.prices(skus), sources, now);
    // 담당 MFC 를 모르면(쇼핑 주소 미선택) 가용은 불명 — 조회하지 않는다
    Map<String, CatalogClient.Stock> stock =
        cart.mfcId() == null
            ? Map.of()
            : fetch("mfc-inventory-service", () -> catalog.stock(cart.mfcId(), skus), sources, now);
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
  private <T> Map<String, T> fetch(
      String system, Supplier<Map<String, T>> call, List<Source> sources, Instant now) {
    try {
      Map<String, T> r = call.get();
      sources.add(new Source(system, true, now));
      return r;
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("장바구니 표시용 {} 조회 실패 — 불명으로 표시", system);
      sources.add(new Source(system, false, now));
      return Map.of();
    }
  }
}
