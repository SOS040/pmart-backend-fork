package com.pmart.product.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 게시 콘텐츠 notices("항목: 값" 글줄) → 상품 상세 칸(2026-11-25 클라이언트 BR-100 R1). 저장은 그대로 글줄이고, 조회 때 나눈다.
 *
 * <ul>
 *   <li>{@link #legalInfo}: 줄마다 첫 ":" 앞을 항목, 뒤를 값으로(상품정보제공고시 표). ":" 가 없거나 앞이 비면 항목 없이 값만.
 *   <li>{@link #origin}: "원산지" 항목 값({@link OriginRules} 와 같은 줄).
 *   <li>{@link #expiryGuide}: 항목 이름에 소비기한·유통기한·품질유지기한·제조연월일이 들어간 첫 줄의 값(LAN 은 "제조연월일, 소비기한 또는
 *       품질유지기한").
 * </ul>
 *
 * 값은 지어내지 않는다 — 줄이 없으면 null.
 */
public final class NoticeRules {

  public record Info(@Nullable String label, String value) {}

  private static final Pattern EXPIRY = Pattern.compile("소비기한|유통기한|품질유지기한|제조연월일");

  private NoticeRules() {}

  /** 줄마다 항목·값. 빈 줄은 뺀다. */
  public static List<Info> legalInfo(List<String> notices) {
    List<Info> out = new ArrayList<>();
    for (String n : notices) {
      if (n == null || n.isBlank()) {
        continue;
      }
      out.add(split(n));
    }
    return out;
  }

  static Info split(String line) {
    String s = line.strip();
    int i = indexOfColon(s);
    if (i <= 0) {
      return new Info(null, s);
    }
    String label = s.substring(0, i).strip();
    String value = s.substring(i + 1).strip();
    return label.isEmpty() ? new Info(null, s) : new Info(label, value);
  }

  private static int indexOfColon(String s) {
    int a = s.indexOf(':');
    int b = s.indexOf('：');
    if (a < 0) {
      return b;
    }
    return b < 0 ? a : Math.min(a, b);
  }

  /** 원산지 값. 없으면 null. */
  public static @Nullable String origin(List<String> notices) {
    return OriginRules.of(notices).map(OriginRules.Origin::text).orElse(null);
  }

  /** 소비기한·제조일 안내 값. 없으면 null. */
  public static @Nullable String expiryGuide(List<String> notices) {
    return firstValue(notices, EXPIRY).orElse(null);
  }

  private static Optional<String> firstValue(List<String> notices, Pattern labelPattern) {
    for (Info info : legalInfo(notices)) {
      if (info.label() != null && labelPattern.matcher(info.label()).find()) {
        String v = info.value().strip();
        if (!v.isEmpty()) {
          return Optional.of(v);
        }
      }
    }
    return Optional.empty();
  }
}
