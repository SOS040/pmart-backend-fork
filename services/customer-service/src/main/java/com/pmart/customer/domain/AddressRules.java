package com.pmart.customer.domain;

import org.jspecify.annotations.Nullable;

/** 주소록 규칙. */
public final class AddressRules {

  private AddressRules() {}

  /** 주소록 최대 건수(정책값). 미설정이면 추가를 보류한다(0/무제한으로 대체 금지). */
  public static boolean limitReached(@Nullable Integer limit, int current) {
    return limit != null && current >= limit;
  }

  /** 주문 때 받는 분·연락처(BR-60, 0.8.0) — 주소 값이 먼저, 없으면 프로필(받는 분 = 별명 → 로그인 제공자 이름). 둘 다 없으면 null. */
  public record Contact(@Nullable String receiverName, @Nullable String receiverPhone) {
    @Override
    public String toString() {
      return "Contact[개인정보 생략]";
    }
  }

  public static Contact contact(
      AddressData address,
      @Nullable String nickname,
      @Nullable String providerName,
      @Nullable String profilePhone) {
    return new Contact(
        firstNonBlank(address.receiverName(), nickname, providerName),
        firstNonBlank(address.receiverPhone(), profilePhone));
  }

  private static @Nullable String firstNonBlank(@Nullable String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  /** 연락처 마스킹(가운데 자리). 원문은 주문 스냅샷·배송 실행만 필요할 때 따로 받는다. 없으면 null. */
  public static @Nullable String maskPhone(@Nullable String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("[^0-9]", "");
    if (digits.length() < 7) {
      return "*".repeat(digits.length());
    }
    String head = digits.substring(0, 3);
    String tail = digits.substring(digits.length() - 4);
    return head + "-" + "*".repeat(digits.length() - 7) + "-" + tail;
  }
}
