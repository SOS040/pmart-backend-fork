package com.pmart.order.orchestration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.order.orchestration.domain.CheckoutRules.PaymentVerdict;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CheckoutRulesTest {

  @Test
  void scheduledWindowIsTheArrivalSlotInSeoul() {
    var w =
        CheckoutRules.capacityWindow(
            "SCHEDULED",
            LocalDate.parse("2026-10-31"),
            "13:00",
            Instant.parse("2026-10-30T15:00:00Z"));
    assertThat(w.iso()).isEqualTo("2026-10-31T04:00:00Z/2026-10-31T05:00:00Z");
  }

  @Test
  void immediateWindowIsTheCurrentHourInSeoul() {
    // 2026-10-31 09:47 KST → 09:00~10:00 KST
    var w =
        CheckoutRules.capacityWindow("SINGLE", null, null, Instant.parse("2026-10-31T00:47:12Z"));
    assertThat(w.iso()).isEqualTo("2026-10-31T00:00:00Z/2026-10-31T01:00:00Z");
  }

  @Test
  void paymentDeadlineIsTheEarlierOfResourcesAndQuote() {
    Instant quote = Instant.parse("2026-10-31T00:10:00Z");
    assertThat(CheckoutRules.paymentDeadline(Instant.parse("2026-10-31T00:09:00Z"), quote))
        .isEqualTo(Instant.parse("2026-10-31T00:09:00Z"));
    assertThat(CheckoutRules.paymentDeadline(Instant.parse("2026-10-31T00:11:00Z"), quote))
        .isEqualTo(quote);
    assertThat(CheckoutRules.paymentDeadline(null, quote)).isEqualTo(quote);
  }

  @Test
  void onlyConfirmedNonApprovalReleasesResources() {
    assertThat(CheckoutRules.verdict("APPROVED")).isEqualTo(PaymentVerdict.APPROVED);
    assertThat(CheckoutRules.verdict("UNKNOWN")).isEqualTo(PaymentVerdict.UNRESOLVED);
    assertThat(CheckoutRules.verdict("MISMATCH")).isEqualTo(PaymentVerdict.UNRESOLVED);
    assertThat(CheckoutRules.verdict("PENDING")).isEqualTo(PaymentVerdict.NOT_APPROVED);
    assertThat(CheckoutRules.verdict("FAILED")).isEqualTo(PaymentVerdict.NOT_APPROVED);
  }

  @Test
  void stepRequestIdsAreStablePerSaga() {
    assertThat(CheckoutRules.requestId("SAGA-1", "RESERVE_INVENTORY"))
        .isEqualTo("SAGA-1:RESERVE_INVENTORY");
    assertThat(CheckoutRules.releaseRequestId("SAGA-1", "SLOT_HOLD"))
        .isEqualTo("SAGA-1:RELEASE:SLOT_HOLD");
    assertThat(CheckoutRules.resourceType("START_PAYMENT")).isNull();
  }
}
