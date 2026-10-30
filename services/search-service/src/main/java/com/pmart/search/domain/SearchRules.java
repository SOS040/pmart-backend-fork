package com.pmart.search.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 검색 색인·조회 규칙. */
public final class SearchRules {

  public static final String ON_SALE = "ON_SALE";
  public static final String TEMPORARILY_SOLD_OUT = "TEMPORARILY_SOLD_OUT";
  public static final String SALE_STOPPED = "SALE_STOPPED";

  /** 1차에 지원하는 정렬. 나머지(인기·평점)는 집계 원천이 없어 관련도로 대체하고 appliedSort 로 알린다. */
  public enum Sort {
    RELEVANCE,
    NEWEST,
    PRICE_ASC,
    PRICE_DESC
  }

  private SearchRules() {}

  /**
   * 카테고리 범위: 노드 id 와 그 조상 id 전부. 카테고리 id 는 구조 id(ADR-0015, 계약 CategoryNode: {@code p03}, {@code
   * p03-c01}, {@code p08-c01-l01-s01})라 '-' 로 나눈 앞부분이 조상이다. 상위 카테고리로 검색하면 하위 상품이 걸린다.
   */
  public static List<String> categoryScope(List<String> categoryIds) {
    Set<String> scope = new LinkedHashSet<>();
    for (String id : categoryIds) {
      if (id == null || id.isBlank()) {
        continue;
      }
      String[] parts = id.split("-");
      StringBuilder b = new StringBuilder();
      for (String part : parts) {
        if (!b.isEmpty()) {
          b.append('-');
        }
        b.append(part);
        scope.add(b.toString());
      }
    }
    return new ArrayList<>(scope);
  }

  /** ProductSaleStateChanged 의 상태(ON_SALE/TEMP_OUT/DISCONTINUED) → API 판매 상태. */
  public static String saleStateFromEvent(String state) {
    return switch (state) {
      case "TEMP_OUT" -> TEMPORARILY_SOLD_OUT;
      case "DISCONTINUED" -> SALE_STOPPED;
      default -> ON_SALE;
    };
  }

  /** 계약 SortKey → 지원 정렬. POPULAR·RATING 은 원천(주문 집계·리뷰)이 없어 RELEVANCE. */
  public static Sort sort(@Nullable String key) {
    if (key == null) {
      return Sort.RELEVANCE;
    }
    return switch (key) {
      case "NEWEST" -> Sort.NEWEST;
      case "PRICE_ASC" -> Sort.PRICE_ASC;
      case "PRICE_DESC" -> Sort.PRICE_DESC;
      default -> Sort.RELEVANCE;
    };
  }

  /** 커서 = 다음 시작 위치(불투명 문자열). 잘못된 커서는 처음부터가 아니라 오류. */
  public static String cursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  public static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!raw.startsWith("o:")) {
        throw new InvalidCursorException();
      }
      int offset = Integer.parseInt(raw.substring(2));
      if (offset < 0) {
        throw new InvalidCursorException();
      }
      return offset;
    } catch (IllegalArgumentException e) {
      throw new InvalidCursorException();
    }
  }

  /** 검색어 정리: 앞뒤 공백·연속 공백. 비면 null(전체). */
  public static @Nullable String normalizeQuery(@Nullable String q) {
    if (q == null) {
      return null;
    }
    String t = q.strip().replaceAll("\\s+", " ");
    return t.isEmpty() ? null : t;
  }

  public static final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException() {
      super("잘못된 커서");
    }
  }
}
