package com.pmart.product.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.product.content.domain.ContentRules.ImageSlot;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentRulesTest {

  static final String SHA_A = "a".repeat(64);
  static final String SHA_B = "b".repeat(64);
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

  @Test
  void availabilityForProductScreens() {
    assertThat(ContentRules.availability(5, false))
        .isEqualTo(new ContentRules.Availability(false, "UNKNOWN"));
    assertThat(ContentRules.availability(null, true).state()).isEqualTo("UNKNOWN");
    assertThat(ContentRules.availability(0, true).state()).isEqualTo("SOLD_OUT");
    assertThat(ContentRules.availability(1, true))
        .as("잔여 수량 노출 임계값 미정 — LOW 를 쓰지 않는다")
        .isEqualTo(new ContentRules.Availability(true, "AVAILABLE"));
  }

  @Test
  void submittableOnlyWithNameMainImageAndLinkedProduct() {
    var main = new ImageSlot(ImageRole.MAIN, 0, SHA_A);
    assertThat(ContentRules.submissionProblems("호밍스 부대찌개", "PRD-1", List.of(main))).isEmpty();

    assertThat(ContentRules.submissionProblems(" ", "PRD-1", List.of(main)))
        .extracting(ContentProblem::code)
        .containsExactly(ContentProblem.NAME_REQUIRED);
    assertThat(
            ContentRules.submissionProblems(
                "이름", "PRD-1", List.of(new ImageSlot(ImageRole.DESCRIPTION, 0, SHA_A))))
        .extracting(ContentProblem::code)
        .containsExactly(ContentProblem.MAIN_IMAGE_REQUIRED);
    assertThat(ContentRules.submissionProblems(null, null, List.of()))
        .extracting(ContentProblem::code)
        .containsExactly(
            ContentProblem.PRODUCT_NOT_LINKED,
            ContentProblem.NAME_REQUIRED,
            ContentProblem.MAIN_IMAGE_REQUIRED);
  }

  @Test
  void mainIsSingleSlotAndSortOrderIsNonNegative() {
    assertThat(ContentRules.slotProblem(ImageRole.MAIN, 0)).isEmpty();
    assertThat(ContentRules.slotProblem(ImageRole.MAIN, 1)).isPresent();
    assertThat(ContentRules.slotProblem(ImageRole.DESCRIPTION, 7)).isEmpty();
    assertThat(ContentRules.slotProblem(ImageRole.DETAIL, -1)).isPresent();
    assertThat(ContentRules.slotProblem(null, 0)).isPresent();
  }

  @Test
  void fileMustBeKnownImageFormatWithinSize() {
    assertThat(ContentRules.fileProblem(JPEG)).isEmpty();
    assertThat(ContentRules.fileProblem(new byte[0])).isPresent();
    assertThat(ContentRules.fileProblem("<svg/>".getBytes())).isPresent();
    assertThat(ContentRules.fileProblem(new byte[ContentRules.MAX_IMAGE_BYTES + 1])).isPresent();
    assertThat(ImageFormat.detect(JPEG)).contains(ImageFormat.JPEG);
    assertThat(
            ImageFormat.detect(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0}))
        .contains(ImageFormat.PNG);
    assertThat(ImageFormat.detect("RIFF0000WEBPVP8 ".getBytes())).contains(ImageFormat.WEBP);
  }

  @Test
  void contentHashIgnoresImageOrderButNotContent() {
    var a = new ImageSlot(ImageRole.MAIN, 0, SHA_A);
    var b = new ImageSlot(ImageRole.DESCRIPTION, 0, SHA_B);
    String h1 = ContentRules.contentHash("n", null, null, null, "[]", "[]", List.of(a, b));
    String h2 = ContentRules.contentHash("n", null, null, null, "[]", "[]", List.of(b, a));
    assertThat(h1).isEqualTo(h2);
    assertThat(ContentRules.contentHash("n2", null, null, null, "[]", "[]", List.of(a, b)))
        .isNotEqualTo(h1);
    assertThat(
            ContentRules.contentHash(
                "n",
                null,
                null,
                null,
                "[]",
                "[]",
                List.of(a, new ImageSlot(ImageRole.DESCRIPTION, 0, SHA_A))))
        .isNotEqualTo(h1);
    assertThat(ContentRules.draftHash(h1, "PRD-1"))
        .isNotEqualTo(ContentRules.draftHash(h1, "PRD-2"));
  }

  @Test
  void objectKeyIsContentAddressedUnderOwnerAndRole() {
    assertThat(ContentRules.objectKey("PRD-1", ImageRole.DESCRIPTION, SHA_A, ImageFormat.JPEG))
        .isEqualTo("products/PRD-1/description/" + SHA_A + ".jpg");
    assertThatThrownBy(() -> ContentRules.objectKey("../x", ImageRole.MAIN, SHA_A, ImageFormat.PNG))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContentRules.objectKey("PRD-1", ImageRole.MAIN, "zz", ImageFormat.PNG))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** BR-19(2026-11-19) — 레퍼런스 예: 1kg 9,990원 → 999원, 500g 5,290원 → 1,058원. */
  @org.junit.jupiter.api.Test
  void unitPricePer100RoundsHalfUp() {
    org.assertj.core.api.Assertions.assertThat(ContentRules.unitPrice(9990, 1000)).isEqualTo(999L);
    org.assertj.core.api.Assertions.assertThat(ContentRules.unitPrice(5290, 500)).isEqualTo(1058L);
    org.assertj.core.api.Assertions.assertThat(ContentRules.unitPrice(3980, 180)).isEqualTo(2211L);
    org.assertj.core.api.Assertions.assertThat(ContentRules.unitPrice(5, 1000))
        .as("0.5 올림")
        .isEqualTo(1L);
    org.assertj.core.api.Assertions.assertThat(ContentRules.unitPrice(1000, 0)).isNull();
  }

  @Test
  void lowStockOnlyWithThresholdAndPositiveStock() {
    assertThat(ContentRules.availability(10, true, 10))
        .isEqualTo(new ContentRules.Availability(true, "LOW", 10));
    assertThat(ContentRules.availability(11, true, 10).state()).isEqualTo("AVAILABLE");
    assertThat(ContentRules.availability(0, true, 10).state()).isEqualTo("SOLD_OUT");
    assertThat(ContentRules.availability(3, true, null).state()).isEqualTo("AVAILABLE");
    assertThat(ContentRules.availability(3, false, 10).state()).isEqualTo("UNKNOWN");
  }
}
