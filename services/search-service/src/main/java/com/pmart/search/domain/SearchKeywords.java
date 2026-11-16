package com.pmart.search.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * 최근 검색어·인기 키워드 규칙(사용자 결정 2026-11-17, Commerce-business-logic.md §6.7·6.9).
 *
 * <ul>
 *   <li>검색어는 앞뒤·연속 공백을 정리하고 100자(코드 포인트)까지 남긴다. 초성 검색어도 입력 그대로 남긴다.
 *   <li>검색자는 (사용자 종류:주체 ID)의 HMAC-SHA256(서버 비밀키) 해시로만 저장한다 — 고객 ID 원문을 색인에 넣지 않는다.
 *   <li>인기 키워드: 서로 다른 검색자 수가 기준 이상인 검색어만, 검색 횟수 순, 숨김 목록 제외.
 * </ul>
 */
public final class SearchKeywords {

  /** 계약 q·keyword maxLength 와 같다. */
  public static final int MAX_LENGTH = 100;

  private SearchKeywords() {}

  /** 기록·삭제에 쓰는 검색어 모양. 비면 null(기록하지 않음). */
  public static @Nullable String normalize(@Nullable String raw) {
    String q = SearchRules.normalizeQuery(raw);
    if (q == null) {
      return null;
    }
    if (q.codePointCount(0, q.length()) > MAX_LENGTH) {
      q = q.substring(0, q.offsetByCodePoints(0, MAX_LENGTH)).strip();
    }
    return q.isEmpty() ? null : q;
  }

  /**
   * 검색자 식별 해시 = HMAC-SHA256(비밀키, 사용자 종류:주체 ID). 같은 사람이면 늘 같고, 비밀키 없이는 고객 ID 를 짐작해 맞춰 볼 수 없다
   * (2026-11-17 — 맨 SHA-256 은 ID 가 짐작 가능하면 되돌릴 수 있다).
   */
  public static String searcherHash(String key, String userType, String subject) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((userType + ":" + subject).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 최근 검색어 문서 id — 같은 검색자·같은 검색어는 한 문서(덮어쓰면 맨 위로 올라간다). */
  public static String recentDocId(String searcherHash, String keyword) {
    return sha256(searcherHash + "\n" + keyword);
  }

  /** 숨김 목록 비교용 모양(공백 정리 + 소문자). */
  public static @Nullable String hiddenForm(@Nullable String keyword) {
    String k = normalize(keyword);
    return k == null ? null : k.toLowerCase(Locale.ROOT);
  }

  /** 집계 결과 한 줄: 검색어, 검색 횟수, 서로 다른 검색자 수. */
  public record KeywordCount(String keyword, long searches, long searchers) {}

  /**
   * 인기 키워드 선택. 서로 다른 검색자 {@code minSearchers} 명 미만·숨김 목록인 검색어를 빼고 검색 횟수 순(같으면 검색자 수, 그다음 검색어 순) 앞에서
   * {@code size} 개.
   */
  public static List<String> popular(
      List<KeywordCount> counted, int size, int minSearchers, List<String> hiddenKeywords) {
    Set<String> hidden =
        hiddenKeywords.stream()
            .map(SearchKeywords::hiddenForm)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    List<KeywordCount> ordered = new ArrayList<>(counted);
    ordered.sort(
        java.util.Comparator.comparingLong(KeywordCount::searches)
            .reversed()
            .thenComparing(java.util.Comparator.comparingLong(KeywordCount::searchers).reversed())
            .thenComparing(KeywordCount::keyword));
    List<String> out = new ArrayList<>();
    for (KeywordCount c : ordered) {
      if (out.size() >= size) {
        break;
      }
      if (c.searchers() < minSearchers || hidden.contains(hiddenForm(c.keyword()))) {
        continue;
      }
      out.add(c.keyword());
    }
    return List.copyOf(out);
  }

  static String sha256(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
