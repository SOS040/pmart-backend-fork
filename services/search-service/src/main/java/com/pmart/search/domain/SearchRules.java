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

  /**
   * 지원 정렬. POPULAR(최근 판매)·RATING(베이지안 별점)·REVIEW_COUNT(리뷰 수)는 2026-11-19 ADR-0072 부터 — storefront
   * 순위 신호가 색인 (products-v6)에 들어온다. 신호가 없는 상품은 그 정렬에서 맨 뒤.
   */
  public enum Sort {
    RELEVANCE,
    NEWEST,
    PRICE_ASC,
    PRICE_DESC,
    POPULAR,
    RATING,
    REVIEW_COUNT,
    /** 0.9.0 BR-83 — onSale=true 와 함께만. 할인 상품 안에서 할인율 높은 순. */
    DISCOUNT_RATE
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

  /** 계약 SortKey → 지원 정렬(모르는 값은 RELEVANCE). */
  public static Sort sort(@Nullable String key) {
    if (key == null) {
      return Sort.RELEVANCE;
    }
    return switch (key) {
      case "NEWEST" -> Sort.NEWEST;
      case "PRICE_ASC" -> Sort.PRICE_ASC;
      case "PRICE_DESC" -> Sort.PRICE_DESC;
      case "POPULAR" -> Sort.POPULAR;
      case "RATING" -> Sort.RATING;
      case "REVIEW_COUNT" -> Sort.REVIEW_COUNT;
      case "DISCOUNT_RATE" -> Sort.DISCOUNT_RATE;
      default -> Sort.RELEVANCE;
    };
  }

  /**
   * 깊은 쪽 상한(2026-11-19, 성능 분석 1순위 ①) — Elasticsearch {@code index.max_result_window}(기본 10000). 이
   * 위치를 넘는 커서는 잘못된 커서(400)이고, 이 너머로는 다음 커서를 주지 않는다(전에는 {@code o:20000} 이 ES 오류로 500).
   */
  public static final int MAX_WINDOW = 10_000;

  /** 이 쪽에서 실제로 읽을 수 있는 크기 — from + size 가 창을 넘지 않게. */
  public static int pageSize(int from, int size) {
    return Math.max(0, Math.min(size, MAX_WINDOW - from));
  }

  /** 다음 커서를 줄지 — 남은 결과가 있고 창 안일 때만. */
  public static boolean hasNext(int pos, long total, boolean pageNotEmpty) {
    return pageNotEmpty && pos < total && pos < MAX_WINDOW;
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
      if (offset < 0 || offset >= MAX_WINDOW) {
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

  /**
   * 초성만 된 검색어인가 — 공백을 빼고 한글 자음(ㄱ~ㅎ)만 2자 이상(2026-11-05). 1자는 너무 많이 걸려 일반 검색으로 둔다. 초성과 완성 글자가 섞인
   * 검색어("ㄷ부")는 아니다.
   */
  public static boolean isChosungQuery(@Nullable String normalizedQuery) {
    if (normalizedQuery == null) {
      return false;
    }
    String t = normalizedQuery.replace(" ", "");
    if (t.length() < 2) {
      return false;
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c < 'ㄱ' || c > 'ㅎ') {
        return false;
      }
    }
    return true;
  }

  public static final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException() {
      super("잘못된 커서");
    }
  }

  /**
   * 원산지 필터 키(products-v8, 2026-11-22 사용자 "원산지는 필터와 검색"). 값은 국내산·수입산, 색인 originType
   * DOMESTIC·IMPORTED.
   */
  public static final String ORIGIN_KEY = "origin";

  /**
   * onSale=true 일 때 읽는 할인 SKU 상한(ES bool 절 상한 1024 안, 2026-11-22 BR-82). 넘으면 그 뒤 할인 상품은 빠진다 — 규모가
   * 커지면 상품 문서에 할인을 싣는다.
   */
  public static final int MAX_ON_SALE_SKUS = 1000;

  /** 메모리에서 줄 세운 목록의 한 쪽(from 부터 size 개, 범위 밖이면 빈 목록). */
  public static <T> java.util.List<T> pageOf(java.util.List<T> all, int from, int size) {
    if (from >= all.size()) {
      return java.util.List.of();
    }
    return java.util.List.copyOf(all.subList(from, Math.min(all.size(), from + size)));
  }

  public static final String ORIGIN_LABEL = "원산지";

  /** 화면 순서. */
  public static final java.util.List<String> ORIGIN_OPTIONS = java.util.List.of("국내산", "수입산");

  /** 이름·원산지·설명을 같이 보는 찾기의 비중 — 이름 일치(3·2·2 가산)보다 훨씬 낮게. */
  public static final double CONTENT_MATCH_BOOST = 0.2;

  public static java.util.List<String> originTypes(java.util.List<String> labels) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String l : labels) {
      if ("국내산".equals(l)) {
        out.add("DOMESTIC");
      } else if ("수입산".equals(l)) {
        out.add("IMPORTED");
      }
    }
    return out; // 모르는 값만이면 빈 목록 — 아무것도 맞지 않음
  }

  public static @org.jspecify.annotations.Nullable String originLabel(String originType) {
    return switch (originType) {
      case "DOMESTIC" -> "국내산";
      case "IMPORTED" -> "수입산";
      default -> null;
    };
  }

  /** 같은 이름 그룹을 묶은 필터 키 앞머리(2026-11-22 BR-81) — "n:중량.200g 이하". 그룹 이름에 '.' 이 있으면 묶지 않는다(키가 갈림). */
  public static final String NAME_FILTER_PREFIX = "n:";

  public static boolean isNameFilterKey(String key) {
    return key.startsWith(NAME_FILTER_PREFIX);
  }

  /** 이름 키면 그 이름의 그룹 id 전부(없으면 빈 목록 — 아무것도 맞지 않음), 아니면 그 키 자체(그룹 id). */
  public static java.util.List<String> filterGroupIds(
      String key, java.util.Map<String, String> namesById) {
    if (!isNameFilterKey(key)) {
      return java.util.List.of(key);
    }
    String name = key.substring(NAME_FILTER_PREFIX.length());
    return namesById.entrySet().stream()
        .filter(e -> e.getValue().equals(name))
        .map(java.util.Map.Entry::getKey)
        .toList();
  }

  /** 필터 화면에 내보낼 키 — 이름에 '.' 이 없으면 이름 키, 있으면 그룹 id. */
  public static String filterKey(String groupId, String name) {
    return name.isBlank() || name.contains(".") ? groupId : NAME_FILTER_PREFIX + name;
  }

  /** 한 요청의 승인 속성 필터 값 상한. */
  public static final int MAX_FILTERS = 30;

  /**
   * 계약 filters(attributeKey.value, 예 capacity.500ml) → 그룹별 값. 첫 '.' 앞이 그룹, 뒤가 옵션(옵션에 '.' 이 있어도 된다).
   * 형식이 틀린 값·빈 값은 버린다(appliedFilters 에 안 나온다). 순서 유지.
   */
  public static java.util.Map<String, java.util.List<String>> parseFilters(
      java.util.@org.jspecify.annotations.Nullable List<String> raw) {
    java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
    if (raw == null) {
      return out;
    }
    int n = 0;
    for (String f : raw) {
      if (f == null || n >= MAX_FILTERS) {
        continue;
      }
      String v = f.strip();
      int dot = v.indexOf('.');
      if (dot <= 0 || dot >= v.length() - 1) {
        continue;
      }
      java.util.List<String> values =
          out.computeIfAbsent(v.substring(0, dot), k -> new java.util.ArrayList<>());
      String option = v.substring(dot + 1);
      if (!values.contains(option)) {
        values.add(option);
        n++;
      }
    }
    return out;
  }
}
