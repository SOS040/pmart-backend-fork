package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 고객 소셜 로그인 제공자별 ID 토큰 검증기(2026-11-19, ADR-0071 — 사용자 "고객 구글 로그인 흐름처럼 카카오랑 네이버도").
 * GOOGLE·KAKAO·NAVER 가 같은 방식(앱이 받은 ID 토큰을 제공자 공개키로 검증)이다. 허용 클라이언트 ID 가 비면 그 제공자만 CONFIG_REQUIRED,
 * 목록에 없는 제공자(APPLE)는 UNSUPPORTED_ACTION. 시험은 같은 타입 빈을 넣어 바꾼다.
 */
public class IdTokenVerifiers {

  public record Entry(IdTokenVerifier verifier, List<String> clientIds) {}

  private final Map<String, Entry> byProvider;

  public IdTokenVerifiers(Map<String, Entry> byProvider) {
    this.byProvider = Map.copyOf(byProvider);
  }

  /** 운영: 설정의 제공자마다 JWKS 검증기. */
  public static IdTokenVerifiers forProperties(CustomerProperties p) {
    return new IdTokenVerifiers(
        Map.of(
            "GOOGLE",
            new Entry(IdTokenVerifier.forGoogle(p.google()), p.google().clientIds()),
            "KAKAO",
            entry(p.kakao()),
            "NAVER",
            entry(p.naver())));
  }

  private static Entry entry(CustomerProperties.Oidc o) {
    return new Entry(
        IdTokenVerifier.forProvider(o.jwkSetUri(), o.clientIds(), o.issuers()), o.clientIds());
  }

  /** 그 제공자의 검증기. 지원하지 않으면 501 UNSUPPORTED_ACTION, 허용 클라이언트 ID 가 없으면 422 CONFIG_REQUIRED. */
  public IdTokenVerifier require(String provider) {
    Entry e = byProvider.get(provider);
    if (e == null) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, provider + " 로그인은 지원하지 않습니다(GOOGLE·KAKAO·NAVER).");
    }
    if (e.clientIds().isEmpty()) {
      throw PmartException.configRequired(
          "pmart.auth.customer." + provider.toLowerCase(Locale.ROOT) + ".client-ids");
    }
    return e.verifier();
  }
}
