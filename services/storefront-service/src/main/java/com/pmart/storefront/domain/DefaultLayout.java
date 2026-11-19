package com.pmart.storefront.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * 기본 진열(layoutVersion 0). 진열 편성 승인(BO-05, {@code /storefront/layout-publications})이 아직 없어서 홈은 HOME
 * 탭 하나에 "게시된 상품 최신순" 섹션 하나만 둔다. 승인된 게시 상품만 보이고 판매중지는 빠진다. 편성 버전이 생기면 이것을 대체한다.
 */
public final class DefaultLayout {

  public static final int VERSION = 0;
  public static final String SECTION_ID = "S-ALL";
  public static final String TITLE = "전체 상품";
  public static final String SUBTITLE = "최근 등록 순";

  /** 같은 조건의 일반 상품 목록(search-service 최신순). */
  public static final String VIEW_ALL_REF = "/api/v1/search/products?sort=NEWEST";

  /** 랭킹 탭 영역(2026-11-20 ADR-0075) — 최근 판매 많은 순. */
  public static final String RANKING_SECTION_ID = "S-RANKING";

  /** 신상품 탭 영역 — 신규 기간 안 처음 게시, 최신 순. */
  public static final String NEW_SECTION_ID = "S-NEW";

  /** 홈 응답에 싣는 첫 묶음 크기. */
  public static final int FIRST_PAGE = 10;

  public static final String SALE_STOPPED = "SALE_STOPPED";

  private DefaultLayout() {}

  /** ProductSaleStateChanged 상태(ON_SALE/TEMP_OUT/DISCONTINUED) → API 판매 상태. */
  public static String saleStateFromEvent(String state) {
    return switch (state) {
      case "TEMP_OUT" -> "TEMPORARILY_SOLD_OUT";
      case "DISCONTINUED" -> SALE_STOPPED;
      default -> "ON_SALE";
    };
  }

  public static String cursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @throws IllegalArgumentException 잘못된 커서
   */
  public static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    if (!raw.startsWith("o:")) {
      throw new IllegalArgumentException("잘못된 커서");
    }
    int offset = Integer.parseInt(raw.substring(2));
    if (offset < 0) {
      throw new IllegalArgumentException("잘못된 커서");
    }
    return offset;
  }
}
