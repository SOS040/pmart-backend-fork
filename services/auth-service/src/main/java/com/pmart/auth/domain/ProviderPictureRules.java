package com.pmart.auth.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 앱이 넘긴 프로필 사진 주소(2026-11-20, 계약 0.8.0 — 사용자 "네이버 프로필 넣어줘야지"). 네이버 ID 토큰에는 사진이 없어(ADR-0071 — 서버는
 * access token 을 쓰지 않는다) 앱이 네이버 프로필 API 로 받은 주소를 넘긴다.
 *
 * <ul>
 *   <li>NAVER 만, ID 토큰에 사진이 없을 때만(있으면 토큰 것)
 *   <li>https·호스트 {@code pstatic.net} 또는 그 하위·포트·사용자 정보 없음·1000자 이하 — 아니면 버린다(로그인은 그대로)
 *   <li>여기서는 주소만 본다. 받아 두기는 customer-service 가 같은 호스트 목록으로(리다이렉트 안 따름·크기 제한 — ProfileImageRules)
 * </ul>
 */
public final class ProviderPictureRules {

  public static final String NAVER_HOST_SUFFIX = "pstatic.net";
  public static final int MAX_LENGTH = 1000;

  private ProviderPictureRules() {}

  /** 사건에 실을 사진 주소: 토큰 것이 우선, 없으면 앱이 넘긴 네이버 주소(맞을 때만). */
  public static @Nullable String pick(
      String provider, @Nullable String fromToken, @Nullable String fromApp) {
    if (fromToken != null && !fromToken.isBlank()) {
      return fromToken;
    }
    return "NAVER".equals(provider) ? naver(fromApp) : null;
  }

  /** 네이버 사진 주소 검사 — 맞으면 정리한 주소, 아니면 null(코드 교환 때 프로필 API 값에도 쓴다, BR-70). */
  public static @Nullable String naver(@Nullable String url) {
    if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
      return null;
    }
    URI u;
    try {
      u = new URI(url.strip());
    } catch (URISyntaxException e) {
      return null;
    }
    String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    boolean hostOk = host.equals(NAVER_HOST_SUFFIX) || host.endsWith("." + NAVER_HOST_SUFFIX);
    if (!"https".equalsIgnoreCase(u.getScheme())
        || !hostOk
        || u.getUserInfo() != null
        || u.getPort() != -1) {
      return null;
    }
    return u.toString();
  }
}
