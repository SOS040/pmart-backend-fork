package com.pmart.employeeauth.application;

import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 토큰에 싣는 직원 역할·센터(ADR-0056 결정 4) = 계정에 직접 둔 값(관리 경로 PUT·이전한 13명) ∪ 지금 유효한 승인 grant(approval 승인, 효력
 * 시작 전·끝난 뒤는 빠진다 — BO-01 "효력 전 접근을 막고 종료 뒤 신규 행위를 막는다"). 토큰 모양이 역할·센터를 따로 담으므로 역할과 센터의 짝은 토큰에 남지
 * 않는다(지금 토큰과 같음). 발급·갱신 때마다 다시 계산한다(access 15분).
 */
@Component
public class EmployeeAccess {

  public record Access(List<String> roles, List<String> centers) {}

  private final EmployeeAuthStore store;

  public EmployeeAccess(EmployeeAuthStore store) {
    this.store = store;
  }

  public Access of(AccountRow account, Instant now) {
    TreeSet<String> roles = new TreeSet<>(account.roles());
    TreeSet<String> centers = new TreeSet<>(account.centers());
    for (EmployeeAuthStore.GrantAccess g : store.activeGrants(account.operatorId(), now)) {
      roles.add(g.role());
      centers.addAll(g.centers());
    }
    return new Access(List.copyOf(roles), List.copyOf(centers));
  }
}
