package com.pmart.product.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 게시 콘텐츠 notices 의 "원산지:" 줄 → 국내/수입 구분(2026-11-22, 검색 products-v8).
 *
 * <ul>
 *   <li>값을 원재료마다 나눈다("쌀: 외국산, 찰현미·곤드레: 국산" → 외국산 · 국산). 앞의 "원재료:" 이름은 뺀다.
 *   <li>값이 모두 국내(국산·국내산·한국·대한민국·국내 지역 이름)면 DOMESTIC, 하나라도 외국(외국산·수입·나라 이름·"○○산")이면 IMPORTED.
 *   <li>그 밖(줄 없음·원양산만·읽을 수 없음)은 판단하지 않는다(null) — 원산지는 지어내지 않는다(법적 문제, 2026-11-21 사용자).
 * </ul>
 */
public final class OriginRules {

  public enum OriginType {
    DOMESTIC,
    IMPORTED
  }

  public record Origin(@Nullable OriginType type, String text) {}

  private static final Pattern PREFIX = Pattern.compile("^(?:\\s*원산지\\s*[:：]\\s*)+");
  private static final Pattern PARTS = Pattern.compile("\\s*[,，/]\\s*|\\s+및\\s+");
  private static final Pattern DOT = Pattern.compile("\\s*[·・]\\s*");
  private static final Pattern PAREN = Pattern.compile("\\([^)]*\\)|\\[[^]]*]");
  private static final Pattern PERCENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");

  /** 국내로 보는 낱말. 지역 이름은 원산지 표시에 흔히 쓰이는 것만. */
  private static final List<String> DOMESTIC =
      List.of(
          "국산", "국내산", "국내", "한국", "대한민국", "한국산", "우리나라", "제주", "강원", "경기", "충청", "충북", "충남", "전라",
          "전북", "전남", "경상", "경북", "경남", "이천", "여주", "철원", "김제", "평택", "보령", "횡성", "청송", "영광", "완도",
          "통영", "남해", "부산", "인천", "서울", "대구", "광주", "대전", "울산", "세종");

  /** 국내도 외국도 아닌 표시 — 이것만 있으면 판단하지 않는다. */
  private static final List<String> NEUTRAL = List.of("원양산", "원양");

  private OriginRules() {}

  /** notices 에서 첫 "원산지:" 줄. 없으면 empty. */
  public static Optional<Origin> of(List<String> notices) {
    for (String n : notices) {
      if (n != null && PREFIX.matcher(n).find()) {
        String text = PREFIX.matcher(n).replaceFirst("").strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(new Origin(classify(text), text));
      }
    }
    return Optional.empty();
  }

  /** 원산지 값 → 국내/수입, 판단 못 하면 null. */
  public static @Nullable OriginType classify(String text) {
    String t = PERCENT.matcher(PAREN.matcher(text).replaceAll(" ")).replaceAll(" ");
    List<String> values = new ArrayList<>();
    for (String part : PARTS.split(t)) {
      String v = part.strip();
      int colon = Math.max(v.lastIndexOf(':'), v.lastIndexOf('：'));
      if (colon >= 0) {
        v = v.substring(colon + 1).strip();
      }
      for (String one : DOT.split(v)) { // 원재료 이름 뒤의 값만 나눈다("미국산·호주산"), 앞의 "멥쌀·흑미" 는 위에서 뺐다
        if (!one.isBlank()) {
          values.add(one.strip());
        }
      }
    }
    boolean domestic = false;
    for (String v : values) {
      String c = v.replace(" ", "");
      if (DOMESTIC.stream().anyMatch(c::startsWith)) {
        domestic = true;
      } else if (NEUTRAL.stream().anyMatch(c::startsWith)) {
        continue;
      } else if (looksForeign(c)) {
        return OriginType.IMPORTED;
      }
    }
    return domestic ? OriginType.DOMESTIC : null;
  }

  /** 외국산·수입·"○○산"·한글 나라 이름(두 글자 이상 한글 낱말 하나) — 국내·원양이 아닌 원산지 값. */
  private static boolean looksForeign(String c) {
    if (c.startsWith("외국") || c.startsWith("수입")) {
      return true;
    }
    if (c.endsWith("산") && c.length() >= 2) {
      return true;
    }
    return c.matches("[가-힣]{2,10}");
  }
}
