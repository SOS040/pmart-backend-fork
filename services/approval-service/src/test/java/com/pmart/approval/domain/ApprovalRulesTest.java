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
  void grantForTheApproverThemselvesIsForbidden() {
    assertCode(() -> ApprovalRules.requireNotOwnGrant("GRANT", "admin-1", "admin-1"), "FORBIDDEN");
    assertThatCode(() -> ApprovalRules.requireNotOwnGrant("GRANT", "emp-9", "admin-1"))
        .doesNotThrowAnyException();
    assertThatCode(() -> ApprovalRules.requireNotOwnGrant("PRICE", "admin-1", "admin-1"))
        .doesNotThrowAnyException(); // GRANT 만 — 다른 종류의 targetRef 는 직원이 아니다
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
    assertThat(ApprovalRules.targetType("MFC_STOCK_ADJUSTMENT")).isEqualTo("MfcStockAdjustment");
    assertThat(ApprovalRules.targetType("MFC_OPERATION_RESTRICTION"))
        .isEqualTo("OperationRestriction");
  }

  @Test
  void mfcApprovalTypesUseTheirConfiguredRoles() {
    // AP-1: 재고 = 본사 재고 승인자, 운영 제한 해제 = 운영 관리자(설정 저장소 approver-roles)
    Map<String, String> roles =
        Map.of(
            "MFC_STOCK_ADJUSTMENT", "INVENTORY_APPROVER",
            "MFC_OPERATION_RESTRICTION", "OPERATOR_ADMIN");
    assertThatCode(
            () ->
                ApprovalRules.requireApprover(
                    "MFC_STOCK_ADJUSTMENT", "sup-1", "hq-1", List.of("INVENTORY_APPROVER"), roles))
        .doesNotThrowAnyException();
    assertCode(
        () ->
            ApprovalRules.requireApprover(
                "MFC_STOCK_ADJUSTMENT", "sup-1", "sup-2", List.of("MFC_SUPERVISOR"), roles),
        "FORBIDDEN");
    assertCode(
        () ->
            ApprovalRules.requireApprover(
                "MFC_OPERATION_RESTRICTION", "sup-1", "hq-1", List.of("INVENTORY_APPROVER"), roles),
        "FORBIDDEN");
  }

  @Test
  void receiptFromTargetServiceSetsExecutionOnlyForApprovedApprovals() {
    // 정본의 회신이 실행 축을 정한다(ADR-0039)
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "APPROVED", "pricing-service", "pricing-service", "REJECTED"))
        .isEqualTo("REJECTED");
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "PARTIALLY_APPROVED", "pricing-service", "pricing-service", "PARTIAL"))
        .isEqualTo("PARTIAL");
    // 다른 수신자의 회신, 반려·심사 중 승인, 모르는 결과는 기록만 한다
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "APPROVED", "pricing-service", "search-service", "APPLIED"))
        .isNull();
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "REJECTED", "pricing-service", "pricing-service", "APPLIED"))
        .isNull();
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "REVIEW_PENDING", "pricing-service", "pricing-service", "APPLIED"))
        .isNull();
    assertThat(
            ApprovalRules.executionAfterReceipt(
                "APPROVED", "pricing-service", "pricing-service", "DONE"))
        .isNull();
  }

  @Test
  void autoApprovalOnlyForRuleRoleAtOwnCenter() {
    List<String> roles = List.of("MFC_WORKER");
    assertThat(
            ApprovalRules.autoApprovable(
                roles, true, "MFC_WORKER", List.of("MFC-01"), "CENTER", "MFC-01"))
        .isTrue();
    assertThat(
            ApprovalRules.autoApprovable(
                roles, true, "MFC_WORKER", List.of("MFC-02"), "CENTER", "MFC-01"))
        .as("다른 센터")
        .isFalse();
    assertThat(
            ApprovalRules.autoApprovable(
                roles, true, "MFC_WORKER", List.of("MFC-01", "MFC-02"), "CENTER", "MFC-01"))
        .as("센터 둘")
        .isFalse();
    assertThat(
            ApprovalRules.autoApprovable(roles, true, "MFC_WORKER", List.of(), "CENTER", "MFC-01"))
        .as("센터 없음")
        .isFalse();
    assertThat(
            ApprovalRules.autoApprovable(
                roles, true, "MFC_WORKER", List.of("MFC-01"), "HQ_DEPARTMENT", "MFC-01"))
        .as("본사 소속")
        .isFalse();
    assertThat(
            ApprovalRules.autoApprovable(
                roles, true, "MFC_SUPERVISOR", List.of("MFC-01"), "CENTER", "MFC-01"))
        .as("다른 역할")
        .isFalse();
  }
}
