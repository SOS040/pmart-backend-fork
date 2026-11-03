package com.pmart.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.pricing.domain.PriceImportRules.ChangeType;
import com.pmart.pricing.domain.PriceImportRules.Classified;
import com.pmart.pricing.domain.PriceImportRules.LineFacts;
import com.pmart.pricing.domain.PriceImportRules.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceImportRulesTest {

  @Test
  void newChangeAndUnchanged() {
    assertThat(PriceImportRules.classify(new LineFacts("E-00000001", 1000L, null, false), false))
        .isEqualTo(new Classified(Status.READY, ChangeType.NEW));
    assertThat(PriceImportRules.classify(new LineFacts("E-00000001", 1200L, 1000L, false), false))
        .isEqualTo(new Classified(Status.READY, ChangeType.CHANGE));
    // 같은 가격은 제출에서 뺀다(사용자 결정 2026-11-03)
    Classified same =
        PriceImportRules.classify(new LineFacts("E-00000001", 1000L, 1000L, false), false);
    assertThat(same).isEqualTo(new Classified(Status.UNCHANGED, ChangeType.UNCHANGED));
    assertThat(same.submittable()).isFalse();
  }

  @Test
  void excludedKeepsChangeType() {
    assertThat(PriceImportRules.classify(new LineFacts("E-00000001", 1200L, 1000L, true), false))
        .isEqualTo(new Classified(Status.EXCLUDED, ChangeType.CHANGE));
  }

  @Test
  void problemsComeFirst() {
    assertThat(PriceImportRules.classify(new LineFacts(null, 1000L, null, false), false).status())
        .isEqualTo(Status.SKU_NOT_FOUND);
    assertThat(PriceImportRules.classify(new LineFacts("E-00000001", null, null, false), false))
        .isEqualTo(new Classified(Status.PRICE_REQUIRED, null));
    // 0원은 받지 않는다(BO-04 0원 허용 미정)
    assertThat(
            PriceImportRules.classify(new LineFacts("E-00000001", 0L, null, false), false).status())
        .isEqualTo(Status.PRICE_REQUIRED);
  }

  @Test
  void laterDuplicateSkuIsMarked() {
    List<Classified> c =
        PriceImportRules.classifyAll(
            List.of(
                new LineFacts("E-00000001", 1000L, null, false),
                new LineFacts("E-00000002", 1000L, null, false),
                new LineFacts("E-00000001", 1500L, null, false),
                new LineFacts(null, 1500L, null, false)));
    assertThat(c)
        .extracting(Classified::status)
        .containsExactly(Status.READY, Status.READY, Status.DUPLICATE_SKU, Status.SKU_NOT_FOUND);
  }
}
