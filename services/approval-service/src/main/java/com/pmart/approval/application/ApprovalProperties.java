package com.pmart.approval.application;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.approval.*}.
 *
 * <p>{@code approver-roles}: 승인 종류 → 승인자 역할(JWT {@code roles}). 업무 문서의 승인선(예: 가격 BO-04)을 운영이 역할로
 * 등록한다. 비어 있는 종류는 결정할 수 없다(CONFIG_REQUIRED). auth-service 의 행위 시점 검증(대행·센터 범위)이 구현되면 그쪽으로 옮긴다.
 */
@ConfigurationProperties("pmart.approval")
public class ApprovalProperties {

  private Map<String, String> approverRoles = new LinkedHashMap<>();

  public Map<String, String> getApproverRoles() {
    return approverRoles;
  }

  public void setApproverRoles(Map<String, String> approverRoles) {
    this.approverRoles = approverRoles;
  }
}
