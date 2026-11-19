package com.pmart.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.6.0 BR-33 — 커서 왕복·틀린 커서, 환불 요약 상태. */
class ClaimListRulesTest {

  @Test
  void cursorRoundTripsAndRejectsGarbage() {
    Instant t = Instant.parse("2026-11-19T01:02:03.456Z");
    String c = ClaimListRules.encode(t, "CLM-1");
    ClaimListRules.Cursor back = ClaimListRules.decode(c).orElseThrow();
    assertThat(back.openedAt()).isEqualTo(t);
    assertThat(back.claimId()).isEqualTo("CLM-1");
    assertThat(ClaimListRules.decode("not-base64!!")).isEmpty();
    assertThat(ClaimListRules.decode("YWJj")).as("구분자 없음").isEmpty();
    assertThat(ClaimListRules.decode(null)).isEmpty();
  }

  @Test
  void refundStateNeverShowsDoneBeforePg() {
    assertThat(ClaimListRules.refundState(List.of())).isEqualTo("NONE");
    assertThat(ClaimListRules.refundState(List.of("COMPLETED", "PG_PENDING")))
        .isEqualTo("IN_PROGRESS");
    assertThat(ClaimListRules.refundState(List.of("COMPLETED", "COMPLETED")))
        .isEqualTo("COMPLETED");
    assertThat(ClaimListRules.refundState(List.of("COMPLETED", "FAILED"))).isEqualTo("FAILED");
  }
}
