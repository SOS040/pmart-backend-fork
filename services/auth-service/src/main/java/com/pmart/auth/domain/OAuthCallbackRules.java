package com.pmart.auth.domain;

import org.jspecify.annotations.Nullable;

/**
 * 네이버 로그인 콜백 중계(2026-11-19, 계약 0.5.0 — 고객 앱 BR-66). 넘길 곳은 앱마다 고정 — 요청에서 목적지를 받지 않는다(열린 리다이렉트 없음).
 * 쿼리는 받은 그대로 붙인다(다시 인코딩하지 않음 — PKCE 교환에서 state·code 가 바뀌면 안 된다).
 */
public final class OAuthCallbackRules {

  private OAuthCallbackRules() {}

  public static final String CUSTOMER_NAVER = "com.pmart.customer://oauth/naver";
  public static final String RIDER_NAVER = "com.pmart.rider://oauth/naver";
  public static final int MAX_QUERY = 4096;

  /** 넘길 주소. 쿼리가 너무 길면 null(400). */
  public static @Nullable String location(String target, @Nullable String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return target;
    }
    if (rawQuery.length() > MAX_QUERY
        || rawQuery.indexOf('\r') >= 0
        || rawQuery.indexOf('\n') >= 0) {
      return null;
    }
    return target + "?" + rawQuery;
  }

  /**
   * 0.7.0(2026-11-20): 302 대신 "앱으로 돌아가기" 페이지. 네이버는 동의 뒤 스크립트로 콜백에 보내는데, 그 이동에 이어진 302 → 앱 주소는
   * Chrome 이 사용자 동작이 없다며 앱을 열지 않았다(LAN 09:19 — 콜백은 왔으나 앱 수신 화면이 뜨지 않음). 페이지가 한 번 넘겨 보고(막히면 그대로),
   * 버튼은 사용자가 누르므로 Chrome 이 앱을 연다. 링크 값은 HTML 속성 이스케이프만 — 쿼리 원문은 그대로(PKCE state·code).
   */
  public static final String SCRIPT =
      "var a=document.getElementById('go');if(a){window.location.replace(a.getAttribute('href'));}";

  /** {@link #SCRIPT} 의 CSP 해시(sha256, base64) — 스크립트를 바꾸면 같이 바꾼다(시험이 확인). */
  public static final String SCRIPT_SHA256 = "nif8nc5p1CvBund6dg9oR+DONGUkoMKb0yKwORutvWg=";

  public static final String CSP =
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-"
          + SCRIPT_SHA256
          + "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

  public static String page(String location) {
    String href = escapeAttribute(location);
    return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<meta name=\"referrer\" content=\"no-referrer\">"
        + "<title>PMart 로그인</title>"
        + "<style>body{margin:0;font-family:sans-serif;display:flex;min-height:100vh;align-items:center;"
        + "justify-content:center;background:#f7f7f7;color:#222}main{text-align:center;padding:24px}"
        + "p{margin:0 0 20px;font-size:16px;line-height:1.5}a{display:inline-block;padding:14px 28px;"
        + "border-radius:999px;background:#03c75a;color:#fff;font-weight:700;text-decoration:none;font-size:17px}"
        + "</style></head><body><main><p>네이버 확인이 끝났어요.<br>PMart 앱으로 돌아가 로그인을 마쳐 주세요.</p>"
        + "<a id=\"go\" href=\""
        + href
        + "\">PMart 앱으로 돌아가기</a></main><script>"
        + SCRIPT
        + "</script></body></html>";
  }

  static String escapeAttribute(String v) {
    StringBuilder b = new StringBuilder(v.length() + 16);
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      switch (c) {
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&#39;");
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }
}
