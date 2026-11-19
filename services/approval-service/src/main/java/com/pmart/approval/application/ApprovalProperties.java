package com.pmart.approval.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.approval.*}.
 *
 * <p>{@code approver-roles}: 승인 종류 → 승인자 역할(JWT {@code roles}). 업무 문서의 승인선(예: 가격 BO-04)을 운영이 역할로
 * 등록한다. 비어 있는 종류는 결정할 수 없다(CONFIG_REQUIRED). auth-service 의 행위 시점 검증(대행·센터 범위)이 구현되면 그쪽으로 옮긴다.
 *
 * <p>{@code auto-approval-rules}: 자동 승인 규칙 표(ADR-0056 결정 6). 설정 저장소(pmart-config, Git)에 두므로 바꾼 기록은
 * 그 저장소 이력에 남는다. 규칙을 바꾸면 {@code version} 을 올린다 — 자동 승인 기록의 승인자 = {@code RULE:<id>@v<version>}.
 */
@ConfigurationProperties("pmart.approval")
public class ApprovalProperties {

  private Map<String, String> approverRoles = new LinkedHashMap<>();
  private List<AutoApprovalRule> autoApprovalRules = new ArrayList<>();

  /**
   * 승인 종류 → 변경 요청을 만들 수 있는 역할(하나라도 있으면). 없는 종류는 누구나(운영자 토큰) 만든다 — 예전 그대로. 첫 사용: 검색 동의어 (ADR-0073,
   * 사용자 2026-11-20 "MD 요청, 관리자 승인") {@code SEARCH_SYNONYM: [MD, OPERATOR_ADMIN]}.
   */
  private Map<String, List<String>> requesterRoles = new LinkedHashMap<>();

  public Map<String, List<String>> getRequesterRoles() {
    return requesterRoles;
  }

  public void setRequesterRoles(Map<String, List<String>> requesterRoles) {
    this.requesterRoles = requesterRoles;
  }

  public Map<String, String> getApproverRoles() {
    return approverRoles;
  }

  public void setApproverRoles(Map<String, String> approverRoles) {
    this.approverRoles = approverRoles;
  }

  public List<AutoApprovalRule> getAutoApprovalRules() {
    return autoApprovalRules;
  }

  public void setAutoApprovalRules(List<AutoApprovalRule> autoApprovalRules) {
    this.autoApprovalRules = autoApprovalRules;
  }

  /**
   * 직원 권한 요청(GRANT) 행 하나를 심사 없이 승인하는 규칙.
   *
   * @param roles 이 역할만
   * @param sameCenterAsAffiliation true 면 소속이 센터이고 권한 센터가 정확히 그 소속 센터 하나일 때만
   */
  public record AutoApprovalRule(
      String id, int version, List<String> roles, boolean sameCenterAsAffiliation) {

    public AutoApprovalRule {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** 결정 기록·사건의 승인자. */
    public String approverId() {
      return "RULE:" + id + "@v" + version;
    }
  }
}
