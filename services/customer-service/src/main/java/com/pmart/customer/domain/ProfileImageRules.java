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
    if (!(scheme.equals("https") || (allowHttp && scheme.equals("http")))) {
      return Optional.empty();
    }
    if (u.getUserInfo() != null
        || u.getHost() == null
        || !hostAllowed(u.getHost(), allowedHostSuffixes)) {
      return Optional.empty();
    }
    return Optional.of(URI.create(sized(u.toString(), sizePx)));
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
  static String sized(String url, int sizePx) {
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
