package com.pmart.cdc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cdc.inventory.domain.SkuHandlingRules.Current;
import com.pmart.cdc.inventory.domain.SkuProjection.SkuState;
import org.junit.jupiter.api.Test;

class SkuHandlingRulesTest {

  @Test
  void unknownSkuCannotBeChanged() {
    assertThat(SkuHandlingRules.rejection(null, Current.ABSENT, true, null))
        .contains(SkuHandlingRules.SKU_NOT_FOUND);
    assertThat(SkuHandlingRules.rejection(null, Current.ABSENT, false, null))
        .contains(SkuHandlingRules.SKU_NOT_FOUND);
  }

  @Test
  void activeSkuCanBeTurnedOnInactiveCannot() {
    assertThat(SkuHandlingRules.rejection(SkuState.ACTIVE, Current.ABSENT, true, null)).isEmpty();
    assertThat(SkuHandlingRules.rejection(SkuState.INACTIVE, Current.ABSENT, true, null))
        .contains(SkuHandlingRules.SKU_INACTIVE);
  }

  @Test
  void turningOffIsAlwaysAllowed() {
    assertThat(SkuHandlingRules.rejection(SkuState.INACTIVE, new Current(true, 3), false, null))
        .isEmpty();
  }

  @Test
  void absentRecordIsOffAtVersionZero() {
    assertThat(Current.ABSENT.enabled()).isFalse();
    assertThat(SkuHandlingRules.rejection(SkuState.ACTIVE, Current.ABSENT, true, 0)).isEmpty();
    assertThat(SkuHandlingRules.rejection(SkuState.ACTIVE, Current.ABSENT, true, 1))
        .contains(SkuHandlingRules.VERSION_CONFLICT);
    assertThat(SkuHandlingRules.rejection(SkuState.ACTIVE, new Current(true, 2), false, 1))
        .contains(SkuHandlingRules.VERSION_CONFLICT);
  }

  @Test
  void sameValueIsNoChange() {
    assertThat(SkuHandlingRules.changes(Current.ABSENT, false)).isFalse();
    assertThat(SkuHandlingRules.changes(Current.ABSENT, true)).isTrue();
    assertThat(SkuHandlingRules.changes(new Current(true, 1), true)).isFalse();
  }

  @Test
  void olderProductVersionIsIgnored() {
    assertThat(ProductVersionGate.acceptsSnapshot(2, 1)).isFalse();
    assertThat(ProductVersionGate.acceptsSnapshot(2, 2)).isTrue();
    assertThat(ProductVersionGate.acceptsSnapshot(2, 3)).isTrue();
    assertThat(ProductVersionGate.acceptsIssued(2, 2)).isFalse();
    assertThat(ProductVersionGate.acceptsIssued(1, 2)).isTrue();
  }

  @Test
  void unitTypeComesFromSkuPrefix() {
    assertThat(SkuProjection.unitTypeOf("E-00000001")).isEqualTo("E");
    assertThat(SkuProjection.unitTypeOf("B-00000002")).isEqualTo("B");
    assertThat(SkuProjection.unitTypeOf("P-00000003")).isNull();
  }
}
