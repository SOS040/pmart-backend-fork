package com.pmart.riderauth.api;

import com.pmart.riderauth.api.generated.model.RiderAuthResult;
import com.pmart.riderauth.api.generated.model.TokenPair;
import com.pmart.riderauth.application.RiderLoginService;
import com.pmart.riderauth.application.RiderTokens;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 응답 변환. */
final class RiderAuthMapping {

  private RiderAuthMapping() {}

  static TokenPair tokens(RiderTokens.Tokens t) {
    return new TokenPair()
        .accessToken(t.accessToken())
        .accessTokenExpiresAt(odt(t.accessTokenExpiresAt()))
        .refreshToken(t.refreshToken())
        .refreshTokenExpiresAt(odt(t.refreshTokenExpiresAt()))
        .sessionId(t.sessionId());
  }

  static RiderAuthResult result(RiderLoginService.Result r) {
    return new RiderAuthResult()
        .authRequestId(r.authRequestId())
        .state(RiderAuthResult.StateEnum.fromValue(r.state()))
        .riderId(r.riderId())
        .newAccount(r.newAccount())
        .tokens(r.tokens() == null ? null : tokens(r.tokens()))
        .occurredAt(odt(r.occurredAt()));
  }

  static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
