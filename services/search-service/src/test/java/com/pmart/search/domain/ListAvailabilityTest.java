package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.domain.ListAvailability.Option;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 목록 판매 가능 여부(계약 0.10.0) — 사용자 2026-11-23 "모든 옵션 품절일 때만". */
class ListAvailabilityTest {

  @Test
  void soldOutOnlyWhenEveryCarriedOptionIsSoldOut() {
    assertThat(ListAvailability.of(List.of(new Option(true, "SOLD_OUT")))).isEqualTo("SOLD_OUT");
    assertThat(
            ListAvailability.of(
                List.of(new Option(true, "SOLD_OUT"), new Option(true, "SOLD_OUT"))))
        .isEqualTo("SOLD_OUT");
    // 취급하지 않는 옵션은 세지 않는다
    assertThat(
            ListAvailability.of(
                List.of(new Option(true, "SOLD_OUT"), new Option(false, "UNKNOWN"))))
        .isEqualTo("SOLD_OUT");
  }

  @Test
  void anySellableCarriedOptionMakesItAvailable() {
    assertThat(
            ListAvailability.of(
                List.of(new Option(true, "SOLD_OUT"), new Option(true, "AVAILABLE"))))
        .isEqualTo("AVAILABLE");
    assertThat(ListAvailability.of(List.of(new Option(true, "LOW")))).isEqualTo("AVAILABLE");
    // 모르는 옵션이 섞여도 팔 수 있는 것이 있으면 AVAILABLE
    assertThat(
            ListAvailability.of(
                List.of(new Option(null, "UNKNOWN"), new Option(true, "AVAILABLE"))))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void unknownWhenNotSure() {
    assertThat(ListAvailability.of(List.of())).isEqualTo("UNKNOWN");
    assertThat(ListAvailability.of(List.of(new Option(false, "UNKNOWN")))).isEqualTo("UNKNOWN");
    // 재고 조회 실패(carried 없음)
    assertThat(ListAvailability.of(List.of(new Option(null, "UNKNOWN")))).isEqualTo("UNKNOWN");
    // 품절 하나 + 취급하는데 모름 하나 → 품절이라고 하지 않는다
    assertThat(
            ListAvailability.of(List.of(new Option(true, "SOLD_OUT"), new Option(true, "UNKNOWN"))))
        .isEqualTo("UNKNOWN");
  }
}
