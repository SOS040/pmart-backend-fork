package com.pmart.customer.domain;

import org.jspecify.annotations.Nullable;

/** 주소록 규칙. */
public final class AddressRules {

  private AddressRules() {}

  /** 주소록 최대 건수(정책값). 미설정이면 추가를 보류한다(0/무제한으로 대체 금지). */
  public static boolean limitReached(@Nullable Integer limit, int current) {
    return limit != null && current >= limit;
  }

  /** 연락처 마스킹(가운데 자리). 원문은 주문 스냅샷·배송 실행만 필요할 때 따로 받는다. */
  public static String maskPhone(String phone) {
    String digits = phone.replaceAll("[^0-9]", "");
    if (digits.length() < 7) {
      return "*".repeat(digits.length());
    }
    String head = digits.substring(0, 3);
    String tail = digits.substring(digits.length() - 4);
    return head + "-" + "*".repeat(digits.length() - 7) + "-" + tail;
  }
}
