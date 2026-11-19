package com.pmart.search.domain;

/**
 * 한글 음절을 자모(호환 자모)로 나눈다(2026-11-20 ADR-0073 오타 교정). "두뷰" → "ㄷㅜㅂㅠ", "두부" → "ㄷㅜㅂㅜ" — 자모 한 글자 차이로 본다.
 * 받침 겹자음·겹모음은 한 자모로 둔다(ㄳ·ㅘ).
 */
public final class Hangul {

  private Hangul() {}

  private static final char[] CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toCharArray();
  private static final char[] JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ".toCharArray();
  private static final String[] JONG = {
    "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ",
    "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
  };

  public static boolean isSyllable(char c) {
    return c >= 0xAC00 && c <= 0xD7A3;
  }

  /** 모두 한글 음절인 낱말인가(교정 대상 — 숫자·영문·초성은 고치지 않는다). */
  public static boolean isHangulWord(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!isSyllable(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static String jamo(String s) {
    StringBuilder b = new StringBuilder(s.length() * 3);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!isSyllable(c)) {
        b.append(c);
        continue;
      }
      int v = c - 0xAC00;
      b.append(CHO[v / (21 * 28)]).append(JUNG[(v % (21 * 28)) / 28]).append(JONG[v % 28]);
    }
    return b.toString();
  }

  /** 자모 길이에 따른 허용 편집 거리 — 짧은 낱말은 1(두 글자 낱말이 엉뚱한 낱말로 바뀌지 않게), 길면 2. */
  public static int fuzziness(String jamo) {
    return jamo.length() <= 6 ? 1 : 2;
  }
}
