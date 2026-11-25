package com.pmart.riderauth.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 사건에 실을 제공자 사진 주소 검사(2026-11-25 — 사용자 "프로필 사진도 해줘", 고객 auth ProviderPictureRules 와 같은 기준). 제공자마다 그
 * 제공자 사진 호스트의 https 만(포트·사용자 정보 없음·1000자 이하). 카카오 ID 토큰 picture 는 http 로 와서 같은 주소의 https 로 바꾼다. 받아
 * 두기(크기·형식·리다이렉트 안 따름)는 rider-service 가 다시 검사한다.
 */
public final class ProviderPictures {

  static final int MAX_LENGTH = 1000;

  private static final Map<String, String> HOSTS =
      Map.of("GOOGLE", "googleusercontent.com", "KAKAO", "kakaocdn.net", "NAVER", "pstatic.net");

  private ProviderPictures() {}

  public static @Nullable String of(String provider, @Nullable String url) {
    String suffix = HOSTS.get(provider);
    if (suffix == null || url == null || url.isBlank() || url.length() > MAX_LENGTH) {
      return null;
    }
    URI u;
    try {
      u = new URI(url.strip());
    } catch (URISyntaxException e) {
      return null;
    }
    String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    if (!(host.equals(suffix) || host.endsWith("." + suffix))
        || u.getUserInfo() != null
        || u.getPort() != -1) {
      return null;
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if ("http".equals(scheme) && "KAKAO".equals(provider)) {
      return "https" + u.toString().substring(4);
    }
    return "https".equals(scheme) ? u.toString() : null;
  }
}
