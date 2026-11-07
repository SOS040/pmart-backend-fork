package com.pmart.mfc.quality.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.mfc.quality.domain.QualityRules.DisposalLine;
import com.pmart.mfc.quality.domain.QualityRules.Line;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QualityRulesTest {

  static final List<DisposalLine> REQUESTED =
      List.of(
          new DisposalLine("E-00000001", "LOT-1", "A-01", null, 3, 0, 0),
          new DisposalLine("E-00000002", "LOT-2", "A-02", null, 1, 0, 0));

  @Test
  void approvalWithoutLinesApprovesEverythingAndPartialMustStayWithinRequest() {
    assertThat(QualityRules.approve(REQUESTED, null))
        .extracting(DisposalLine::approvedQty)
        .containsExactly(3, 1);
    assertThat(
            QualityRules.approve(
                REQUESTED, List.of(new Line("E-00000001", "LOT-1", "A-01", 2, null))))
        .extracting(DisposalLine::approvedQty)
        .containsExactly(2, 0);
    assertThatThrownBy(
            () ->
                QualityRules.approve(
                    REQUESTED, List.of(new Line("E-00000001", "LOT-1", "A-01", 4, null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                QualityRules.approve(
                    REQUESTED, List.of(new Line("E-00000009", "LOT-1", "A-01", 1, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void executionCountsPendingAgainstRemainderAndCompletesWhenAllExecuted() {
    List<DisposalLine> approved =
        QualityRules.approve(REQUESTED, List.of(new Line("E-00000001", "LOT-1", "A-01", 2, null)));
    Line one = new Line("E-00000001", "LOT-1", "A-01", 1, null);
    assertThat(QualityRules.exceeds(approved, Map.of(), List.of(one))).isNull();
    // 진행 중 실행 1 + 요청 2 > 승인 2
    assertThat(
            QualityRules.exceeds(
                approved,
                Map.of(one.key(), 1),
                List.of(new Line("E-00000001", "LOT-1", "A-01", 2, null))))
        .isNotNull();
    // 승인 0 줄은 실행 불가
    assertThat(
            QualityRules.exceeds(
                approved, Map.of(), List.of(new Line("E-00000002", "LOT-2", "A-02", 1, null))))
        .isNotNull();
    List<DisposalLine> after = QualityRules.addExecuted(approved, List.of(one));
    assertThat(QualityRules.stateAfterExecution(after)).isEqualTo("PARTIALLY_EXECUTED");
    after = QualityRules.addExecuted(after, List.of(one));
    assertThat(QualityRules.stateAfterExecution(after)).isEqualTo("COMPLETED");
  }
}
