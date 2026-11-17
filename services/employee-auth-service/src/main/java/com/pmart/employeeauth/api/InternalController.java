package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.InternalApi;
import com.pmart.employeeauth.api.generated.model.EmployeeRole;
import com.pmart.employeeauth.api.generated.model.EmployeeRoleList;
import com.pmart.employeeauth.api.generated.model.InternalAccountAccess;
import com.pmart.employeeauth.api.generated.model.InternalGrant;
import com.pmart.employeeauth.api.generated.model.InternalGrantList;
import com.pmart.employeeauth.application.EmployeeAdminService;
import com.pmart.employeeauth.application.EmployeeRoleCatalog;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi(0.11.0, 2026-11-17 HR-2) — hr-service 권한 요청 검사용 서비스 간 조회(토큰 없는 내부 호출, 게이트웨이가 막는다).
 * 역할 목록과 한 직원의 grant(이름·승인자·사유 글은 싣지 않는다).
 */
@RestController
public class InternalController implements InternalApi {

  private final EmployeeRoleCatalog catalog;
  private final EmployeeAdminService admin;

  public InternalController(EmployeeRoleCatalog catalog, EmployeeAdminService admin) {
    this.catalog = catalog;
    this.admin = admin;
  }

  @Override
  public ResponseEntity<EmployeeRoleList> listEmployeeRolesInternal() {
    return ResponseEntity.ok(
        new EmployeeRoleList()
            .items(
                catalog.forService().stream()
                    .map(
                        r ->
                            new EmployeeRole()
                                .code(r.code())
                                .name(r.name())
                                .centerScoped(r.centerScoped())
                                .order(r.order()))
                    .toList()));
  }

  @Override
  public ResponseEntity<InternalGrantList> getEmployeeGrantsInternal(
      String operatorId, @Nullable Boolean activeOrFuture) {
    // 0.12.0(HR-2 ③): 계정에 직접 둔 역할·센터도 — hr-service 가 중복·섞임 검사에 쓴다
    InternalAccountAccess account =
        admin
            .accountForService(operatorId)
            .map(a -> new InternalAccountAccess().roles(a.roles()).centers(a.centers()))
            .orElse(null);
    return ResponseEntity.ok(
        new InternalGrantList()
            .operatorId(operatorId)
            .account(account)
            .items(
                admin.grantsForService(operatorId, Boolean.TRUE.equals(activeOrFuture)).stream()
                    .map(
                        g ->
                            new InternalGrant()
                                .grantId(g.grantId())
                                .role(g.role())
                                .centers(g.centers())
                                .validFrom(EmployeeAuthMapping.odt(g.validFrom()))
                                .validUntil(
                                    g.validUntil() == null
                                        ? null
                                        : EmployeeAuthMapping.odt(g.validUntil()))
                                .revokedAt(
                                    g.revokedAt() == null
                                        ? null
                                        : EmployeeAuthMapping.odt(g.revokedAt())))
                    .toList()));
  }
}
