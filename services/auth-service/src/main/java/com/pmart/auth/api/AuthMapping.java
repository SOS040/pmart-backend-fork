package com.pmart.auth.api;

import com.pmart.auth.api.generated.model.TokenPair;
import com.pmart.auth.application.SessionTokens;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환. */
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

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
