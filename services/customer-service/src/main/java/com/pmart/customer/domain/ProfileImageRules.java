package com.pmart.customer.domain;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 제공자 프로필 사진을 우리 저장소로 옮길 때의 규칙(ADR-0024).
 *
 * <ul>
 *   <li>받는 주소는 허용한 호스트(기본 {@code googleusercontent.com} 하위)의 https 만 — 사건에 실린 임의 주소로 서버가 요청하지
 *       않게(SSRF).
 *   <li>Google 사진 주소의 크기 지정({@code =s96-c})은 우리가 정한 크기로 바꾼다.
 *   <li>본문은 상한 이하이고 실제 이미지 형식(JPEG·PNG·WebP·GIF 머리)이어야 한다. 응답 Content-Type 만 믿지 않는다.
 * </ul>
 */
public final class ProfileImageRules {

  private ProfileImageRules() {}

  /** 받아도 되는 주소면 크기를 맞춘 URI, 아니면 빈 값. */
  public static Optional<URI> fetchUri(
      @Nullable String url, List<String> allowedHostSuffixes, boolean allowHttp, int sizePx) {
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    URI u;
    try {
      u = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    // 카카오 ID 토큰 picture 는 http://k.kakaocdn.net/… 로 온다(2026-11-25 LAN) — 같은 주소가 https 로도 된다. 허용
    // 호스트면 https 로 바꿔 받는다
    if (scheme.equals("http")
        && !allowHttp
        && u.getHost() != null
        && hostAllowed(u.getHost(), List.of("kakaocdn.net"))
        && hostAllowed(u.getHost(), allowedHostSuffixes)) {
      u = URI.create("https" + u.toString().substring(4));
      scheme = "https";
    }
    if (!(scheme.equals("https") || (allowHttp && scheme.equals("http")))) {
      return Optional.empty();
    }
    if (u.getUserInfo() != null
        || u.getHost() == null
        || !hostAllowed(u.getHost(), allowedHostSuffixes)) {
      return Optional.empty();
    }
    return Optional.of(URI.create(sized(u.toString(), u.getHost(), sizePx)));
  }

  static boolean hostAllowed(String host, List<String> suffixes) {
    String h = host.toLowerCase(Locale.ROOT);
    for (String s : suffixes) {
      String suffix = s.toLowerCase(Locale.ROOT).trim();
      if (!suffix.isEmpty() && (h.equals(suffix) || h.endsWith("." + suffix))) {
        return true;
      }
    }
    return false;
  }

  /** Google 사진 주소 끝의 크기 지정({@code =s96-c}, {@code =s96})을 {@code =s<size>-c} 로. 없으면 그대로. */
  /** 네이버 CDN 크기(지원: s80·s160 — f160_160 같음). */
  static final String NAVER_TYPE = "s160";

  /** 크기를 바꾼 주소로 못 받았을 때 다시 받을 원래 주소(같으면 null). */
  public static @Nullable URI original(URI fetched, String source) {
    URI o = URI.create(source.strip());
    return o.equals(fetched) ? null : o;
  }

  static String sized(String url, String host, int sizePx) {
    // 네이버(*.pstatic.net, 2026-11-25 BR-103): 원본이 1.45MB PNG 라 1MB 상한에 걸려 사본이 늘 실패 — CDN 축소
    // ?type=s<크기>(같은 사진 25KB)
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("pstatic.net") || h.endsWith(".pstatic.net")) {
      // 네이버 CDN 은 정해진 크기만 준다(s80·s160 — s256 등은 404, 2026-11-25 LAN 확인). 256px 칸에 s160
      return url.replaceFirst("[?#].*$", "") + "?type=" + NAVER_TYPE;
    }
    return url.replaceFirst("=s\\d+(-c)?$", "=s" + sizePx + "-c");
  }

  /** 본문 머리로 판정한 이미지 형식(Content-Type). 이미지가 아니면 빈 값. */
  public static Optional<String> imageType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
      return Optional.of("image/gif");
    }
    return Optional.empty();
  }
}
