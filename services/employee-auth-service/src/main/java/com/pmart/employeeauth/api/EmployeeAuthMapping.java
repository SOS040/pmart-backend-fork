package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.model.Employee;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.EmployeeState;
import com.pmart.employeeauth.api.generated.model.TokenPair;
import com.pmart.employeeauth.application.EmployeeLoginService;
import com.pmart.employeeauth.application.EmployeeTokens;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 응답 변환. */
final class EmployeeAuthMapping {

  private EmployeeAuthMapping() {}

  static TokenPair tokens(EmployeeTokens.Tokens t) {
    return new TokenPair()
        .accessToken(t.accessToken())
        .accessTokenExpiresAt(odt(t.accessTokenExpiresAt()))
        .refreshToken(t.refreshToken())
        .refreshTokenExpiresAt(odt(t.refreshTokenExpiresAt()))
        .sessionId(t.sessionId());
  }

  static EmployeeLoginResult login(EmployeeLoginService.LoginResult r) {
    return new EmployeeLoginResult()
        .operatorId(r.operatorId())
        .roles(r.roles())
        .centers(r.centers())
        .tokens(tokens(r.tokens()));
  }

  static Employee employee(EmployeeAuthStore.AccountRow a) {
    return new Employee()
        .operatorId(a.operatorId())
        .state(EmployeeState.fromValue(a.state()))
        .roles(a.roles())
        .centers(a.centers())
        .keycloakLinked(a.keycloakLinked())
        .lastLoginAt(a.lastLoginAt() == null ? null : odt(a.lastLoginAt()))
        .version(a.version())
        .updatedAt(odt(a.updatedAt()));
  }

  static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
