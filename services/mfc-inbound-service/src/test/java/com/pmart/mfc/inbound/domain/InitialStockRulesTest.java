package com.pmart.mfc.inbound.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inbound.domain.InitialStockRules.Plan;
import com.pmart.mfc.inbound.domain.InitialStockRules.RawLine;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InitialStockRulesTest {

  static final String A = "E-00000001";
  static final String B = "B-00000002";

  @Test
  void linesAreGroupedPerCenterInSkuOrder() {
    Plan p =
        InitialStockRules.plan(
            List.of(
                new RawLine(B, "MFC-2", B, 3),
                new RawLine(B, "MFC-1", B, 2),
                new RawLine(null, "MFC-1", A, 5)));
    assertThat(p.valid()).isTrue();
    assertThat(p.byCenter().keySet()).containsExactly("MFC-1", "MFC-2");
    assertThat(p.byCenter().get("MFC-1")).containsExactly(Map.entry(B, 2), Map.entry(A, 5));
    assertThat(p.byCenter().get("MFC-2")).containsExactly(Map.entry(B, 3));
  }

  @Test
  void anyInvalidLineRejectsTheWholeApproval() {
    List<RawLine> lines =
        List.of(
            new RawLine(A, "MFC-1", A, 5),
            new RawLine("X", "MFC-1", B, 1),
            new RawLine(B, "MFC-1", "bad", 1),
            new RawLine(B, null, B, 1),
            new RawLine(B, "MFC-1", B, 0),
            new RawLine(A, "MFC-1", A, 1));
    Plan p = InitialStockRules.plan(lines);
    assertThat(p.valid()).isFalse();
    assertThat(p.problems()).hasSize(5);
    assertThat(p.rejectReason()).startsWith("INVALID: ").contains("lineKey", "중복");
    assertThat(p.soleCenter(lines)).isEqualTo("MFC-1");
    assertThat(InitialStockRules.plan(List.of()).valid()).isFalse();
  }

  @Test
  void soleCenterIsNullWhenSeveralCenters() {
    List<RawLine> lines = List.of(new RawLine(A, "MFC-1", A, 0), new RawLine(A, "MFC-2", A, 1));
    assertThat(InitialStockRules.plan(lines).soleCenter(lines)).isNull();
  }

  @Test
  void backoffDoublesUpToFiveMinutes() {
    assertThat(InitialStockRules.backoff(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(InitialStockRules.backoff(2)).isEqualTo(Duration.ofSeconds(10));
    assertThat(InitialStockRules.backoff(4)).isEqualTo(Duration.ofSeconds(40));
    assertThat(InitialStockRules.backoff(20)).isEqualTo(InitialStockRules.MAX_BACKOFF);
  }
}
