package com.pmart.customerassistant.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * AI 상담 질문 금칙어 대조(2026-11-23 CA-2, case 0.37.0 {@code domain/ProfanityRules} 와 같은 규칙 — 서비스 경계 때문에
 * 코드를 공유하지 않고 같은 규칙으로 둔다).
 *
 * <p>소문자로 바꾸고 띄어쓰기·문장부호를 지운 뒤 포함 비교 — "시 발", "시.발" 같은 띄어 쓰기도 잡는다. 목록이 비면 아무것도 잡지 않는다. 글·단어는 로그에 남기지
 * 않는다.
 */
public final class ProfanityRules {

  private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·…~※★☆♡♥]+");

  private ProfanityRules() {}

  public static String normalize(@Nullable String s) {
    return s == null ? "" : NOISE.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /** 글 가운데 하나라도 목록의 단어를 담고 있으면 true. */
  public static boolean contains(List<String> words, @Nullable String... texts) {
    if (words.isEmpty()) {
      return false;
    }
    StringBuilder all = new StringBuilder();
    for (String t : texts) {
      all.append(normalize(t)).append('\u0000');
    }
    String hay = all.toString();
    for (String w : words) {
      String n = normalize(w);
      if (!n.isEmpty() && hay.contains(n)) {
        return true;
      }
    }
    return false;
  }
}
