package com.pmart.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalRulesTest {

  private static final Map<String, String> ROLES = Map.of("PRICE", "PRICE_APPROVER");

  private static void assertCode(Runnable r, String code) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(PmartException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void approverWithConfiguredRoleWhoIsNotTheRequesterPasses() {
    assertThatCode(
            () ->
                ApprovalRules.requireApprover(
                    "PRICE", "md-1", "lead-1", List.of("PRICE_APPROVER"), ROLES))
        .doesNotThrowAnyException();
  }

  @Test
  void selfApprovalIsForbiddenEvenWithTheRole() {
    assertCode(
        () ->
            ApprovalRules.requireApprover(
                "PRICE", "lead-1", "lead-1", List.of("PRICE_APPROVER"), ROLES),
        "FORBIDDEN");
  }

  @Test
  void missingRoleIsForbidden() {
    assertCode(
        () -> ApprovalRules.requireApprover("PRICE", "md-1", "op-2", List.of("MD"), ROLES),
        "FORBIDDEN");
  }

  @Test
  void unconfiguredApprovalTypeIsConfigRequiredNotOpenToAnyone() {
    assertCode(
        () ->
            ApprovalRules.requireApprover(
                "PRODUCT", "md-1", "op-2", List.of("PRICE_APPROVER"), ROLES),
        "CONFIG_REQUIRED");
  }

  @Test
  void stateGuardsAndSupportedDecisions() {
    assertThatCode(() -> ApprovalRules.requireDraft("DRAFT")).doesNotThrowAnyException();
    assertCode(() -> ApprovalRules.requireDraft("SUBMITTED"), "INVALID_STATE");
    assertCode(() -> ApprovalRules.requireUnderReview("APPROVED"), "INVALID_STATE");
    assertCode(() -> ApprovalRules.requireSupported("PARTIALLY_APPROVE"), "UNSUPPORTED_ACTION");
    assertCode(() -> ApprovalRules.requireSupported("REQUEST_EVIDENCE"), "UNSUPPORTED_ACTION");
    assertThat(ApprovalRules.decidedState("APPROVE")).isEqualTo("APPROVED");
    assertThat(ApprovalRules.decidedState("REJECT")).isEqualTo("REJECTED");
    assertThat(ApprovalRules.targetType("PRICE")).isEqualTo("Price");
  }
}
