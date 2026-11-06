package com.pmart.order.orchestration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.order.orchestration.domain.FulfillmentRules.AfterReject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FulfillmentRulesTest {

  static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  static final Duration W = Duration.ofMinutes(10);

  static AfterReject after(String reason, Instant slot, Instant first, int version) {
    return FulfillmentRules.afterReject(reason, NOW, slot, first, W, 2, version);
  }

  @Test
  void onlyInventoryMismatchAndOperationRestrictedWait() {
    assertThat(after("INVENTORY_MISMATCH", null, NOW, 1)).isEqualTo(AfterReject.WAIT);
    assertThat(after("OPERATION_RESTRICTED", null, NOW, 1)).isEqualTo(AfterReject.WAIT);
    assertThat(after("CAPACITY_EXCEEDED", null, NOW, 1)).isEqualTo(AfterReject.CANCEL);
    assertThat(after("ALREADY_STOPPED", null, NOW, 1)).isEqualTo(AfterReject.CANCEL);
    assertThat(after("LOT_INELIGIBLE", null, NOW, 1)).isEqualTo(AfterReject.CANCEL);
  }

  @Test
  void slotStartedWindowExpiredOrVersionsUsedCancel() {
    // 슬롯 시작이 지났다
    assertThat(after("INVENTORY_MISMATCH", NOW.minusSeconds(1), NOW, 1))
        .isEqualTo(AfterReject.CANCEL);
    assertThat(after("INVENTORY_MISMATCH", NOW.plusSeconds(60), NOW, 1))
        .isEqualTo(AfterReject.WAIT);
    // 처음 거절부터 W 가 지났다(한 번만 센다)
    assertThat(after("INVENTORY_MISMATCH", null, NOW.minus(W), 2)).isEqualTo(AfterReject.CANCEL);
    assertThat(after("INVENTORY_MISMATCH", null, NOW.minus(W).plusSeconds(1), 2))
        .isEqualTo(AfterReject.WAIT);
    // 새 버전 최대 2번: 버전 3 이 거절되면 끝
    assertThat(after("INVENTORY_MISMATCH", null, NOW, 2)).isEqualTo(AfterReject.WAIT);
    assertThat(after("INVENTORY_MISMATCH", null, NOW, 3)).isEqualTo(AfterReject.CANCEL);
  }

  @Test
  void missingCenterSettingsIsConfigRequired() {
    assertThat(FulfillmentRules.afterReject("INVENTORY_MISMATCH", NOW, null, NOW, null, 2, 1))
        .isEqualTo(AfterReject.CONFIG_REQUIRED);
    assertThat(FulfillmentRules.afterReject("INVENTORY_MISMATCH", NOW, null, NOW, W, null, 1))
        .isEqualTo(AfterReject.CONFIG_REQUIRED);
    // 재시도 대상이 아니면 설정과 무관하게 취소
    assertThat(FulfillmentRules.afterReject("CAPACITY_EXCEEDED", NOW, null, NOW, null, null, 1))
        .isEqualTo(AfterReject.CANCEL);
  }

  @Test
  void reservationRecoveredOnlyWhenConfirmedPartsCoverEachSku() {
    var m = JsonMapper.builder().build();
    JsonNode request =
        m.readTree(
            "{\"shipments\":[{\"lines\":[{\"sku\":\"E-1\",\"qty\":2},{\"sku\":\"E-2\",\"qty\":1}]}]}");
    JsonNode ok =
        m.readTree(
            "{\"state\":\"CONFIRMED\",\"parts\":[{\"sku\":\"E-1\",\"qty\":2,\"state\":\"CONFIRMED\"},"
                + "{\"sku\":\"E-2\",\"qty\":1,\"state\":\"CONFIRMED\"}]}");
    JsonNode blocked =
        m.readTree(
            "{\"state\":\"CONFIRMED\",\"parts\":[{\"sku\":\"E-1\",\"qty\":2,\"state\":\"BLOCKED\"},"
                + "{\"sku\":\"E-2\",\"qty\":1,\"state\":\"CONFIRMED\"}]}");
    JsonNode released = m.readTree("{\"state\":\"RELEASED\",\"parts\":[]}");
    assertThat(FulfillmentRules.reservationRecovered(ok, request)).isTrue();
    assertThat(FulfillmentRules.reservationRecovered(blocked, request)).isFalse();
    assertThat(FulfillmentRules.reservationRecovered(released, request)).isFalse();
    assertThat(FulfillmentRules.reservationRecovered(null, request)).isFalse();
  }
}
