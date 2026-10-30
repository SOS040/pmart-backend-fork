package com.pmart.product.domain;

/** 제조사 바코드 분류. 숫자만으로 된 GTIN 길이로만 나눈다(검증 숫자 계산 규칙은 업무 문서에 없어 강제하지 않는다). 선행 0 은 그대로 보존한다. */
public enum BarcodeType {
  GTIN_8,
  GTIN_12,
  GTIN_13,
  GTIN_14,
  OTHER;

  public static BarcodeType classify(String barcode) {
    if (!barcode.chars().allMatch(Character::isDigit)) {
      return OTHER;
    }
    return switch (barcode.length()) {
      case 8 -> GTIN_8;
      case 12 -> GTIN_12;
      case 13 -> GTIN_13;
      case 14 -> GTIN_14;
      default -> OTHER;
    };
  }

  /** 앞뒤 공백만 걷어낸다. 선행 0·하이픈 등 본문은 바꾸지 않는다. */
  public static String normalize(String raw) {
    return raw == null ? "" : raw.strip();
  }
}
