package com.pmart.storefront.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DefaultLayoutTest {

  @Test
  void saleStateFromEvent() {
    assertThat(DefaultLayout.saleStateFromEvent("TEMP_OUT")).isEqualTo("TEMPORARILY_SOLD_OUT");
    assertThat(DefaultLayout.saleStateFromEvent("DISCONTINUED")).isEqualTo("SALE_STOPPED");
    assertThat(DefaultLayout.saleStateFromEvent("ON_SALE")).isEqualTo("ON_SALE");
  }

  @Test
  void cursor() {
    assertThat(DefaultLayout.offset(DefaultLayout.cursor(10))).isEqualTo(10);
    assertThat(DefaultLayout.offset(null)).isZero();
    assertThatThrownBy(() -> DefaultLayout.offset("bm90LWEtY3Vyc29y"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
