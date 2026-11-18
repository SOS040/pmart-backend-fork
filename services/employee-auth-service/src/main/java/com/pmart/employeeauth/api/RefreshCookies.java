package com.pmart.employeeauth.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;

/**
 * 웹 앱 refresh 쿠키·CSRF 쿠키(ADR-0059). HTTP 모양만 — 값의 의미(소유권·서명)는 application 이 본다.
 *
 * <ul>
 *   <li>{@value #REFRESH} — refresh token. HttpOnly·Secure·SameSite=Strict·Path=갱신
 *       주소/{sessionId}(세션마다 따로 실린다)·Max-Age = 이 refresh token 의 만료까지(갱신마다 새로).
 *   <li>{@value #CSRF_PREFIX}{sessionId} — Signed Double-Submit 값. JS 가 읽는다(HttpOnly
 *       아님)·Secure·SameSite=Strict·Path=/ ·Max-Age = 세션 최대 길이(세션은 그보다 길지 않다). 이름에 sessionId 를 넣어
 *       탭·앱(포트)끼리 덮어쓰지 않는다.
 * </ul>
 *
 * Max-Age(2026-11-18, clients web-common C1): 전에는 없어서(브라우저 세션 쿠키) 로그아웃 없이 끝난 세션의 CSRF 쿠키가 남아 같은
 * 호스트(백오피스 4개 — 포트만 다름)의 모든 요청 머리말에 쌓였고 431 이 났다. 이제 세션이 끝날 무렵 저절로 사라진다.
 *
 * <p>게이트웨이와 서비스 경로가 같다(재작성 없음) — Path 는 브라우저가 보는 게이트웨이 경로 그대로.
 */
final class RefreshCookies {

  static final String REFRESH = "__Secure-pmart_ea_rt";
  static final String CSRF_PREFIX = "__Secure-pmart_ea_csrf_";
  static final String REFRESH_PATH_PREFIX = "/api/v1/employee-auth/tokens/refresh/";

  private RefreshCookies() {}

  /**
   * 세션을 열 때: refresh + CSRF.
   *
   * @param refreshMaxAge refresh token 만료까지
   * @param csrfMaxAge 세션 최대 길이(CSRF 는 세션 동안 다시 내지 않는다)
   */
  static List<String> open(
      String sessionId,
      String refreshToken,
      Duration refreshMaxAge,
      String csrfValue,
      Duration csrfMaxAge) {
    return List.of(
        refresh(sessionId, refreshToken, refreshMaxAge),
        csrf(sessionId, csrfValue, positive(csrfMaxAge)));
  }

  /** 갱신 때: 새 refresh 만(CSRF 는 세션 동안 그대로). Max-Age = 새 refresh token 만료까지. */
  static String refresh(String sessionId, String refreshToken, Duration maxAge) {
    return ResponseCookie.from(REFRESH, refreshToken)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(REFRESH_PATH_PREFIX + sessionId)
        .maxAge(positive(maxAge))
        .build()
        .toString();
  }

  /** 0 이하면 1초 — Max-Age=0 은 "지금 지워라"라서 막 낸 쿠키에 쓰지 않는다. */
  private static Duration positive(Duration d) {
    return d.isNegative() || d.isZero() ? Duration.ofSeconds(1) : d;
  }

  /** 로그아웃 때: 둘 다 지운다(같은 이름·Path·속성 + Max-Age=0). */
  static List<String> clear(String sessionId) {
    return List.of(
        ResponseCookie.from(REFRESH, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path(REFRESH_PATH_PREFIX + sessionId)
            .maxAge(Duration.ZERO)
            .build()
            .toString(),
        csrf(sessionId, "", Duration.ZERO));
  }

  private static String csrf(String sessionId, String value, @Nullable Duration maxAge) {
    ResponseCookie.ResponseCookieBuilder b =
        ResponseCookie.from(CSRF_PREFIX + sessionId, value)
            .httpOnly(false)
            .secure(true)
            .sameSite("Strict")
            .path("/");
    if (maxAge != null) {
      b.maxAge(maxAge);
    }
    return b.build().toString();
  }

  static @Nullable String read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (name.equals(c.getName())) {
        return c.getValue();
      }
    }
    return null;
  }
}
