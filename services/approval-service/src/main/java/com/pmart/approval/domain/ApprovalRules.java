package com.pmart.approval.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * 승인 최소 범위의 업무 규칙(ADR-0004·0014, approval-service.yaml).
 *
 * <ul>
 *   <li>제출은 DRAFT 에서만. 결정은 심사 중(REVIEW_PENDING)에서만.
 *   <li>발의자 = 승인자이면 FORBIDDEN(자기승인 분리).
 *   <li>승인자 역할은 종류별 설정값. 미설정이면 CONFIG_REQUIRED — 누구나 승인 가능으로 대체하지 않는다.
 *   <li>이 단계가 지원하는 결정은 APPROVE·REJECT. PARTIALLY_APPROVE·REQUEST_EVIDENCE 는 UNSUPPORTED_ACTION.
 * </ul>
 */
public final class ApprovalRules {

  public static final Set<String> SUPPORTED_DECISIONS = Set.of("APPROVE", "REJECT");

  private ApprovalRules() {}

  public static void requireDraft(String requestState) {
    if (!"DRAFT".equals(requestState)) {
      throw PmartException.invalidState("제출은 DRAFT 상태에서만 할 수 있습니다(현재 " + requestState + ").");
    }
  }

  public static void requireUnderReview(String reviewState) {
    if (!"REVIEW_PENDING".equals(reviewState)) {
      throw PmartException.invalidState("심사 중인 승인만 결정할 수 있습니다(현재 " + reviewState + ").");
    }
  }

  public static void requireSupported(String decision) {
    if (!SUPPORTED_DECISIONS.contains(decision)) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, decision + " 결정은 아직 지원하지 않습니다(최소 범위: APPROVE, REJECT).");
    }
  }

  /**
   * @param approverRoles 결정자 토큰의 역할
   * @param approverRolesByType 종류 → 승인 역할(설정값)
   */
  public static void requireApprover(
      String type,
      String requesterId,
      String approverId,
      List<String> approverRoles,
      Map<String, String> approverRolesByType) {
    if (requesterId.equals(approverId)) {
      throw forbidden("발의자는 자기 요청을 승인할 수 없습니다.");
    }
    String required = approverRolesByType.get(type);
    if (required == null || required.isBlank()) {
      throw PmartException.configRequired("pmart.approval.approver-roles." + type);
    }
    if (!approverRoles.contains(required)) {
      throw forbidden(type + " 승인 권한이 없습니다.");
    }
  }

  /** ApprovalDecided.decision (APPROVED / REJECTED). */
  public static String decidedState(String decision) {
    return "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
  }

  /** ApprovalDecided.targetRef.targetType — 정본 aggregate 종류. */
  public static String targetType(String type) {
    return switch (type) {
      case "PRODUCT" -> "Product";
      case "PRICE" -> "Price";
      case "DISPLAY" -> "Display";
      case "CENTER_MASTER" -> "Center";
      case "SERVICEABILITY" -> "ServiceabilityVersion";
      default -> type;
    };
  }

  public static PmartException forbidden(String message) {
    return new PmartException(
        ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, message, false, List.of());
  }
}
