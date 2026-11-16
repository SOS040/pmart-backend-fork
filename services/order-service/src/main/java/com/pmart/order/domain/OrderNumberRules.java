package com.pmart.order.domain;

import java.security.SecureRandom;
import org.jspecify.annotations.Nullable;

/**
 * 화면용 주문번호(2026-11-16 — 클라이언트 all 요청): 숫자 8자리, 첫 자리 1~9(앞 0 이 잘리지 않게). 전체에서 겹치지 않는 무작위 번호 — 일련번호면
 * 주문량과 남의 다음 번호를 짐작할 수 있다. 겹침은 저장 때 고유 색인으로 막고 다시 뽑는다.
 */
public final class OrderNumberRules {

  private static final SecureRandom RANDOM = new SecureRandom();

  private OrderNumberRules() {}

  public static String next() {
    return String.valueOf(10_000_000 + RANDOM.nextInt(90_000_000));
  }

  /** 검색어 정리: 공백·하이픈을 빼고 숫자 8자리면 그 값, 아니면 null. */
  public static @Nullable String normalize(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String d = raw.replaceAll("[\\s-]", "");
    return d.matches("[0-9]{8}") ? d : null;
  }
}
