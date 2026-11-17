package com.pmart.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.approval.domain.ApprovalRefRules.Ask;
import com.pmart.approval.domain.ApprovalRefRules.Facts;
import com.pmart.approval.domain.ApprovalRefRules.Reason;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ApprovalRefRulesTest {

  static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
  static final String TYPE = "MFC_STOCK_ADJUSTMENT";
  static final String SERVICE = "mfc-inventory-service";
  static final String REF = "MFC-01:COUNT_ADJUSTMENT:CNT-1";

  static Facts approved(@Nullable String usedRef) {
    return new Facts(TYPE, SERVICE, REF, "APPROVED", "hq-1", null, null, usedRef);
  }

  static Ask ask(@Nullable String actorId, @Nullable String useRef) {
    return new Ask(TYPE, SERVICE, REF, actorId, useRef);
  }

  @Test
  void approvedMatchingUnusedApprovalIsUsable() {
    assertThat(ApprovalRefRules.reason(approved(null), ask("sup-1", "U1"), NOW)).isNull();
    assertThat(ApprovalRefRules.reason(approved(null), ask(null, null), NOW)).isNull();
  }

  @Test
  void typeAndTargetMustMatchExactly() {
    assertThat(
            ApprovalRefRules.reason(
                approved(null),
                new Ask("MFC_OPERATION_RESTRICTION", SERVICE, REF, null, null),
                NOW))
        .isEqualTo(Reason.TYPE_MISMATCH);
    assertThat(
            ApprovalRefRules.reason(
                approved(null), new Ask(TYPE, "mfc-quality-service", REF, null, null), NOW))
        .isEqualTo(Reason.TARGET_MISMATCH);
    assertThat(
            ApprovalRefRules.reason(
                approved(null),
                new Ask(TYPE, SERVICE, "MFC-02:COUNT_ADJUSTMENT:CNT-1", null, null),
                NOW))
        .as("다른 센터")
        .isEqualTo(Reason.TARGET_MISMATCH);
  }

  @Test
  void onlyApprovedApprovalsAreUsable() {
    for (String state : new String[] {"REVIEW_PENDING", "REJECTED", "EXPIRED", "SUPERSEDED"}) {
      Facts f = new Facts(TYPE, SERVICE, REF, state, null, null, null, null);
      assertThat(ApprovalRefRules.reason(f, ask(null, null), NOW))
          .as(state)
          .isEqualTo(Reason.NOT_APPROVED);
    }
    // 종류가 다르면 그것부터(정본이 엉뚱한 번호를 실었다)
    Facts pending = new Facts(TYPE, SERVICE, REF, "REVIEW_PENDING", null, null, null, null);
    assertThat(ApprovalRefRules.reason(pending, new Ask("PRICE", SERVICE, REF, null, null), NOW))
        .isEqualTo(Reason.TYPE_MISMATCH);
  }

  @Test
  void approverCannotExecuteTheirOwnApproval() {
    assertThat(ApprovalRefRules.reason(approved(null), ask("hq-1", "U1"), NOW))
        .isEqualTo(Reason.SELF_APPROVAL);
  }

  @Test
  void usedOnceSameUseRefPassesOtherIsAlreadyUsed() {
    assertThat(ApprovalRefRules.reason(approved("U1"), ask(null, "U1"), NOW)).isNull();
    assertThat(ApprovalRefRules.reason(approved("U1"), ask(null, "U2"), NOW))
        .isEqualTo(Reason.ALREADY_USED);
    assertThat(ApprovalRefRules.reason(approved("U1"), ask(null, null), NOW))
        .as("확인만(useRef 없음)")
        .isEqualTo(Reason.ALREADY_USED);
  }

  @Test
  void effectiveWindow() {
    Facts later =
        new Facts(TYPE, SERVICE, REF, "APPROVED", "hq-1", NOW.plusSeconds(60), null, null);
    assertThat(ApprovalRefRules.reason(later, ask(null, "U1"), NOW))
        .isEqualTo(Reason.NOT_YET_EFFECTIVE);
    Facts expired = new Facts(TYPE, SERVICE, REF, "APPROVED", "hq-1", null, NOW, null);
    assertThat(ApprovalRefRules.reason(expired, ask(null, "U1"), NOW))
        .as("만료 시각 = 지금이면 만료")
        .isEqualTo(Reason.EXPIRED);
    Facts open =
        new Facts(
            TYPE, SERVICE, REF, "APPROVED", "hq-1", NOW.minusSeconds(1), NOW.plusSeconds(1), null);
    assertThat(ApprovalRefRules.reason(open, ask(null, "U1"), NOW)).isNull();
  }

  @Test
  void sameUseRefStillPassesAfterExpiry() {
    // 쓴 시점에 유효했고 같은 집행의 재시도다 — 만료로 재시도를 막지 않는다
    Facts usedThenExpired =
        new Facts(TYPE, SERVICE, REF, "APPROVED", "hq-1", null, NOW.minusSeconds(1), "U1");
    assertThat(ApprovalRefRules.reason(usedThenExpired, ask(null, "U1"), NOW)).isNull();
    assertThat(ApprovalRefRules.reason(usedThenExpired, ask(null, "U2"), NOW))
        .isEqualTo(Reason.ALREADY_USED);
  }
}
