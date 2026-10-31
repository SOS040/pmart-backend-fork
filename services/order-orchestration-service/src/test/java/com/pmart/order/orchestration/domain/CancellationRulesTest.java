package com.pmart.order.orchestration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.order.orchestration.domain.CancellationRules.StopVerdict;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CancellationRulesTest {

  final ObjectMapper mapper = new ObjectMapper();

  StopVerdict verdict(String json) {
    return CancellationRules.verdict(mapper.readTree(json));
  }

  @Test
  void stopVerdict() {
    // 작업 지시 전(포장 없음)·전 포장 STOPPED → 진행
    assertThat(verdict("{\"state\":\"RESOLVED\",\"packages\":[]}")).isEqualTo(StopVerdict.STOPPED);
    assertThat(
            verdict(
                "{\"state\":\"RESOLVED\",\"packages\":[{\"verdict\":\"STOPPED\"},{\"verdict\":\"STOPPED\"}]}"))
        .isEqualTo(StopVerdict.STOPPED);
    // 판정 대기·인계 확인 중 → 기다림
    assertThat(verdict("{\"state\":\"PENDING\",\"packages\":[]}")).isEqualTo(StopVerdict.WAIT);
    assertThat(
            verdict(
                "{\"state\":\"RESOLVED\",\"packages\":[{\"verdict\":\"STOPPED\"},{\"verdict\":\"HANDOVER_IN_PROGRESS\"}]}"))
        .isEqualTo(StopVerdict.WAIT);
    // 하나라도 인계됨 → 출고 전 취소 불가(대기 중인 포장이 있어도)
    assertThat(
            verdict(
                "{\"state\":\"PENDING\",\"packages\":[{\"verdict\":\"HANDOVER_IN_PROGRESS\"},{\"verdict\":\"HANDED_OVER\"}]}"))
        .isEqualTo(StopVerdict.HANDED_OVER);
  }

  @Test
  void releaseRules() {
    assertThat(CancellationRules.releaseReason("INVENTORY_RESERVATION"))
        .isEqualTo("CANCEL_BEFORE_PICK");
    assertThat(CancellationRules.releaseReason("CAPACITY_HOLD")).isEqualTo("ORDER_CANCELLED");
    assertThat(CancellationRules.releaseReason("SLOT_HOLD")).isEqualTo("ORDER_CANCELLED");
    assertThat(CancellationRules.released("RELEASED")).isTrue();
    assertThat(CancellationRules.released("EXPIRED")).isTrue();
    assertThat(CancellationRules.released("CONFIRMED")).isFalse();
    assertThat(CancellationRules.released(null)).isFalse();
    assertThat(CancellationRules.next("STOP_WORK")).isEqualTo("RELEASE_RESOURCES");
    assertThat(CancellationRules.next("APPLY_ORDER_CANCEL")).isNull();
  }
}
