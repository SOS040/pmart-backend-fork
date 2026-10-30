package com.pmart.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 주문 목록 cursor — 확정 시각(마이크로초까지)과 주문 ID 를 그대로 되돌린다. 손상된 값은 VALIDATION_FAILED. */
class OrderCursorTest {

  @Test
  void roundTripAndRejectsGarbage() {
    Instant at = Instant.parse("2026-10-31T01:02:03.123456Z");
    String c = OrderService.encodeCursor(at, "ORD-1");
    String[] p = OrderService.decodeCursor(c);
    assertThat(Instant.parse(p[0])).isEqualTo(at);
    assertThat(p[1]).isEqualTo("ORD-1");
    assertThatThrownBy(() -> OrderService.decodeCursor("not-a-cursor"))
        .isInstanceOf(PmartException.class);
    assertThatThrownBy(() -> OrderService.decodeCursor("%%%")).isInstanceOf(PmartException.class);
  }
}
