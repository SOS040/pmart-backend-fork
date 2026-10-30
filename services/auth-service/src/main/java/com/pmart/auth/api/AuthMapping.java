package com.pmart.auth.api;

import com.pmart.auth.api.generated.model.OperatorAccount;
import com.pmart.auth.api.generated.model.TokenPair;
import com.pmart.auth.application.SessionTokens;
import com.pmart.auth.domain.OperatorRules;
import com.pmart.auth.infrastructure.OperatorStore.AccountRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환. 비밀번호 해시는 내보내지 않는다. */
final class AuthMapping {

  private AuthMapping() {}

  static TokenPair tokens(SessionTokens.Tokens t) {
    return new TokenPair()
        .accessToken(t.accessToken())
        .accessTokenExpiresAt(odt(t.accessTokenExpiresAt()))
        .refreshToken(t.refreshToken())
        .refreshTokenExpiresAt(odt(t.refreshTokenExpiresAt()))
        .sessionId(t.sessionId());
  }

  static OperatorAccount account(AccountRow a, Instant now) {
    boolean locked = OperatorRules.locked(a.lockedUntil(), now);
    return new OperatorAccount()
        .operatorId(a.operatorId())
        .loginId(a.loginId())
        .displayName(a.displayName())
        .roles(a.roles())
        .centers(a.centers())
        .state(OperatorAccount.StateEnum.fromValue(a.state()))
        .locked(locked)
        .lockedUntil(locked ? odt(a.lockedUntil()) : null)
        .passwordChangeRequired(a.passwordChangeRequired())
        .lastLoginAt(odt(a.lastLoginAt()))
        .version(a.version())
        .createdAt(odt(a.createdAt()));
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
