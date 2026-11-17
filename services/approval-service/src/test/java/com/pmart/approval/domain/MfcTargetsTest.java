package com.pmart.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.approval.domain.MfcTargets.Line;
import com.pmart.common.web.PmartException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MfcTargetsTest {

  static final List<Line> ONE = List.of(new Line("E-00000001", 25));

  static void assertInvalid(
      String type, String service, String ref, List<Line> lines, String field) {
    assertThatThrownBy(() -> MfcTargets.validate(type, service, ref, lines))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
              assertThat(e.details()).anySatisfy(d -> assertThat(d.field()).isEqualTo(field));
            });
  }

  @Test
  void validStockAdjustmentKindsPass() {
    assertThatCode(
            () ->
                MfcTargets.validate(
                    "MFC_STOCK_ADJUSTMENT",
                    "mfc-inventory-service",
                    "MFC-01:COUNT_ADJUSTMENT:CNT-1",
                    ONE))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                MfcTargets.validate(
                    "MFC_STOCK_ADJUSTMENT", "mfc-inventory-service", "MFC-01:CORRECTION:C-9", ONE))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                MfcTargets.validate(
                    "MFC_STOCK_ADJUSTMENT", "mfc-quality-service", "MFC-01:DISPOSAL:DSP-1", ONE))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                MfcTargets.validate(
                    "MFC_STOCK_ADJUSTMENT",
                    "mfc-quality-service",
                    "MFC-01:QUALITY_RELEASE:QD-1",
                    ONE))
        .doesNotThrowAnyException();
  }

  @Test
  void restrictionLiftNeedsNoLines() {
    assertThatCode(
            () ->
                MfcTargets.validate(
                    "MFC_OPERATION_RESTRICTION",
                    "mfc-capacity-service",
                    "MFC-01:RESTRICTION_LIFT:OPR-1",
                    List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void otherTypesAreNotChecked() {
    assertThat(MfcTargets.isMfcType("PRICE")).isFalse();
    assertThatCode(() -> MfcTargets.validate("PRICE", "pricing-service", "E-1", List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void targetRefFormatKindAndServiceAreChecked() {
    String t = "MFC_STOCK_ADJUSTMENT";
    String inv = "mfc-inventory-service";
    assertInvalid(t, inv, "MFC-01:COUNT_ADJUSTMENT", ONE, "targetRef");
    assertInvalid(t, inv, "MFC-01:COUNT_ADJUSTMENT:CNT-1:X", ONE, "targetRef");
    assertInvalid(t, inv, "MFC-01::CNT-1", ONE, "targetRef");
    assertInvalid(t, inv, "MFC 01:COUNT_ADJUSTMENT:CNT-1", ONE, "targetRef");
    assertInvalid(t, inv, "MFC-01:RESTRICTION_LIFT:OPR-1", ONE, "targetRef"); // 다른 종류의 kind
    assertInvalid(t, "mfc-quality-service", "MFC-01:COUNT_ADJUSTMENT:CNT-1", ONE, "targetService");
    assertInvalid(
        "MFC_OPERATION_RESTRICTION",
        "mfc-inventory-service",
        "MFC-01:RESTRICTION_LIFT:OPR-1",
        List.of(),
        "targetService");
  }

  @Test
  void stockAdjustmentNeedsSkuAndQtyLines() {
    String t = "MFC_STOCK_ADJUSTMENT";
    String inv = "mfc-inventory-service";
    String ref = "MFC-01:COUNT_ADJUSTMENT:CNT-1";
    assertInvalid(t, inv, ref, List.of(), "lines");
    assertInvalid(t, inv, ref, List.of(new Line(null, 3)), "lines[0].lineKey");
    assertInvalid(t, inv, ref, List.of(new Line("E-1", 0)), "lines[0].requestedQty");
    assertInvalid(
        t, inv, ref, List.of(new Line("E-1", 2), new Line("E-2", null)), "lines[1].requestedQty");
  }

  @Test
  void parseSplitsThreeParts() {
    assertThat(MfcTargets.parse("MFC-01:DISPOSAL:DSP-1"))
        .isEqualTo(new MfcTargets.Target("MFC-01", "DISPOSAL", "DSP-1"));
    assertThat(MfcTargets.parse(null)).isNull();
    assertThat(MfcTargets.parse("a:b:")).isNull();
  }
}
