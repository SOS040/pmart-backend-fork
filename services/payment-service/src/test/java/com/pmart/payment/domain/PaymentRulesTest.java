package com.pmart.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.payment.infrastructure.toss.TossProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {

  static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

  @Test
  void tossResponseMustMatchAttemptExactly() {
    String id = "pay-0123456789abcdef0123456789abcdef";
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, id, "pk-1", 27100L, "KRW")).isEmpty();
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, "pay-other", "pk-1", 27100L, "KRW"))
        .contains("ORDER_REF");
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, id, "pk-2", 27100L, "KRW"))
        .contains("ORDER_REF");
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, id, "pk-1", 27101L, "KRW"))
        .contains("AMOUNT");
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, id, "pk-1", 27100L, "USD"))
        .contains("CURRENCY");
    // 대소문자·공백도 다른 값(R6 가공 금지)
    assertThat(PaymentRules.mismatch(id, "pk-1", 27100, id.toUpperCase(), "pk-1", 27100L, "KRW"))
        .contains("ORDER_REF");
  }

  @Test
  void unknownScheduleFastFiveThenEscalateThenSlow() {
    for (int i = 1; i < 5; i++) {
      var n = PaymentRules.nextCheck(i, NOW);
      assertThat(n.at()).isEqualTo(NOW.plusSeconds(60));
      assertThat(n.escalate()).isFalse();
    }
    var fifth = PaymentRules.nextCheck(5, NOW);
    assertThat(fifth.escalate()).isTrue();
    assertThat(fifth.at()).isEqualTo(NOW.plusSeconds(900));
    var later = PaymentRules.nextCheck(6, NOW);
    assertThat(later.escalate()).isFalse();
    assertThat(later.at()).isEqualTo(NOW.plusSeconds(900));
  }

  @Test
  void refundLimitAndExpiryAndAuthFailure() {
    // OMS §10.2: 27,100 결제 후 9,000 성공 → 18,100
    assertThat(PaymentRules.remaining(27100, 9000, 0)).isEqualTo(18100);
    assertThat(PaymentRules.remaining(27100, 9000, 5000)).isEqualTo(13100);
    assertThat(PaymentRules.resourcesExpired(NOW, NOW)).isTrue();
    assertThat(PaymentRules.resourcesExpired(NOW.plusSeconds(1), NOW)).isFalse();
    assertThat(PaymentRules.authFailureStage("PAY_PROCESS_CANCELED"))
        .isEqualTo(PaymentStage.CANCELED_BY_USER);
    assertThat(PaymentRules.authFailureStage("REJECT_CARD_COMPANY"))
        .isEqualTo(PaymentStage.AUTH_FAILED);
    assertThat(PaymentRules.ORDER_ID.matcher("pay-0123456789abcdef0123456789abcdef").matches())
        .isTrue();
    assertThat(PaymentRules.ORDER_ID.matcher("pay 1").matches()).isFalse();
  }

  @Test
  void stageSummaryMatchesContract() {
    assertThat(PaymentStage.READY.state()).isEqualTo("CREATED");
    assertThat(PaymentStage.CONFIRMING.state()).isEqualTo("PENDING");
    assertThat(PaymentStage.MANUAL_REVIEW.state()).isEqualTo("UNKNOWN");
    assertThat(PaymentStage.MISMATCH_HOLD.state()).isEqualTo("MISMATCH");
    assertThat(PaymentStage.EXPIRED.state()).isEqualTo("FAILED");
    assertThat(PaymentStage.PARTIAL_CANCELED.state()).isEqualTo("PARTIALLY_CANCELLED");
  }

  @Test
  void liveKeysAreRefusedOutsideProduction() {
    assertThatThrownBy(
            () -> new TossProperties(null, "live_gsk_x", "live_gck_x", false, null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("live_gsk_x");
    assertThatThrownBy(
            () -> new TossProperties(null, "test_sk_x", "test_ck_x", false, null, null, null))
        .hasMessageContaining("gsk");
    assertThat(
            new TossProperties(null, "test_gsk_x", "test_gck_x", false, null, null, null).baseUrl())
        .isEqualTo("https://api.tosspayments.com");
    assertThat(
            new TossProperties(null, "live_gsk_x", "live_gck_x", true, null, null, null)
                .secretKey())
        .isEqualTo("live_gsk_x");
  }
}
