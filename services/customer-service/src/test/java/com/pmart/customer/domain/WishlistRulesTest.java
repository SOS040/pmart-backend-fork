package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customer.domain.WishlistRules.Option;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 찜 목록 대표 판매 상태·가격(ADR-0081). */
class WishlistRulesTest {

  @Test
  void unknownWhenNoCard() {
    assertThat(WishlistRules.display(null)).isEqualTo(new WishlistRules.Display("UNKNOWN", null));
    assertThat(WishlistRules.display(List.of()).saleState()).isEqualTo("UNKNOWN");
  }

  @Test
  void onSaleWhenAnySellingOptionIsNotSoldOutAtCenter() {
    var d =
        WishlistRules.display(
            List.of(
                new Option("ON_SALE", "SOLD_OUT", 3000L),
                new Option("ON_SALE", "AVAILABLE", 5000L)));
    assertThat(d).isEqualTo(new WishlistRules.Display("ON_SALE", 3000L));
    // 센터를 모르면(가용 없음) 판매 중으로 — 품절이라 단정하지 않는다
    assertThat(WishlistRules.display(List.of(new Option("ON_SALE", null, null))).saleState())
        .isEqualTo("ON_SALE");
  }

  @Test
  void soldOutWhenEverySellingOptionIsSoldOutOrTemporarilyStopped() {
    assertThat(WishlistRules.display(List.of(new Option("ON_SALE", "SOLD_OUT", 3000L))).saleState())
        .isEqualTo("SOLD_OUT");
    assertThat(
            WishlistRules.display(List.of(new Option("TEMPORARILY_SOLD_OUT", null, 3000L)))
                .saleState())
        .isEqualTo("SOLD_OUT");
  }

  @Test
  void endedWhenAllOptionsStoppedAndPriceFallsBackToAnyOption() {
    var d =
        WishlistRules.display(
            List.of(
                new Option("SALE_STOPPED", null, null), new Option("SALE_STOPPED", null, 7000L)));
    assertThat(d).isEqualTo(new WishlistRules.Display("ENDED", 7000L));
  }
}
