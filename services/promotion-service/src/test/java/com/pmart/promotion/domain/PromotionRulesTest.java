package com.pmart.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.promotion.domain.PromotionRules.CouponPolicy;
import com.pmart.promotion.domain.PromotionRules.Discount;
import com.pmart.promotion.domain.PromotionRules.Input;
import com.pmart.promotion.domain.PromotionRules.Target;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionRulesTest {

  static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

  @Test
  void unitDiscountFloorsRateAndNeverExceedsPrice() {
    assertThat(PromotionRules.unitDiscount(new Discount("RATE", 15, null), 1990)).isEqualTo(298);
    assertThat(PromotionRules.unitDiscount(new Discount("AMOUNT", 500, null), 300)).isEqualTo(300);
    assertThat(PromotionRules.ratePercent(1990, 298)).isEqualTo(14);
  }

  @Test
  void couponDiscountRespectsMaxAndItemAmount() {
    assertThat(PromotionRules.couponDiscount(new Discount("RATE", 10, 2000), 30_000))
        .isEqualTo(2000);
    assertThat(PromotionRules.couponDiscount(new Discount("RATE", 10, 2000), 15_555))
        .isEqualTo(1555);
    assertThat(PromotionRules.couponDiscount(new Discount("AMOUNT", 3000, null), 2000))
        .isEqualTo(2000);
  }

  @Test
  void validUntilIsEndOfNthDayInSeoul() {
    CouponPolicy p = new CouponPolicy("DOWNLOAD", 0, null, 1, 7, null);
    // 2026-11-20 23:30 KST 에 받으면 받은 날 포함 7일 — 11-26 23:59:59.999 KST 까지
    Instant issued = Instant.parse("2026-11-20T14:30:00Z");
    assertThat(PromotionRules.validUntil(p, issued, ZoneId.of("Asia/Seoul")))
        .isEqualTo(Instant.parse("2026-11-26T14:59:59.999Z"));
  }

  @Test
  void phaseFollowsApprovalAndTime() {
    Instant s = NOW.minusSeconds(60);
    Instant e = NOW.plusSeconds(3600);
    assertThat(PromotionRules.phase("SUBMITTED", s, e, null, NOW)).isEqualTo("NOT_APPROVED");
    assertThat(PromotionRules.phase("APPROVED", s, e, null, NOW)).isEqualTo("RUNNING");
    assertThat(PromotionRules.phase("APPROVED", NOW.plusSeconds(1), e, null, NOW))
        .isEqualTo("SCHEDULED");
    assertThat(PromotionRules.phase("APPROVED", s, NOW, null, NOW)).isEqualTo("FINISHED");
    assertThat(PromotionRules.phase("ENDED", s, e, NOW, NOW)).isEqualTo("FINISHED");
  }

  @Test
  void categoryAncestorsFollowIdPrefix() {
    assertThat(PromotionRules.withAncestors("p09-c01-l01"))
        .containsExactly("p09", "p09-c01", "p09-c01-l01");
  }

  @Test
  void checkFindsProblemsPerKind() {
    Input noTargets =
        new Input(
            "PRICE_DISCOUNT",
            "겨울 할인",
            NOW,
            NOW.plusSeconds(3600),
            new Discount("RATE", 95, null),
            List.of(),
            null);
    assertThat(PromotionRules.check(noTargets, NOW).stream().map(PromotionRules.Problem::field))
        .contains("targets", "discount.value");
    Input okPrice =
        new Input(
            "PRICE_DISCOUNT",
            "겨울 할인",
            NOW,
            NOW.plusSeconds(3600),
            new Discount("RATE", 10, null),
            List.of(new Target("SKU", "E-00000001"), new Target("CATEGORY", "p09")),
            null);
    assertThat(PromotionRules.check(okPrice, NOW)).isEmpty();
    Input badCoupon =
        new Input(
            "COUPON",
            "가입 축하",
            NOW,
            NOW.plusSeconds(3600),
            new Discount("AMOUNT", 3000, 1000),
            List.of(),
            new CouponPolicy("SIGNUP", 2000, null, 1, 7, NOW.plusSeconds(99999)));
    assertThat(PromotionRules.check(badCoupon, NOW).stream().map(PromotionRules.Problem::field))
        .contains("coupon.useDays", "discount.maxAmount", "discount.value");
  }
}
