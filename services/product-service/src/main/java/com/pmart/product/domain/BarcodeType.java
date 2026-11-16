package com.pmart.product.domain;

/**
 * 제조사 바코드 분류. 숫자만으로 된 GTIN 길이로 나눈다. 선행 0 은 그대로 보존한다. GTIN(8·12·13·14자리)은 GS1 검사 숫자를 초안 검증에서
 * 확인한다(2026-11-17 사용자 결정 "상품 정보 수정 + 승인", {@link #checkDigitValid}). 그 밖의 형식(OTHER)은 검사하지 않는다.
 */
public enum BarcodeType {
  GTIN_8,
  GTIN_12,
  GTIN_13,
  GTIN_14,
  OTHER;

  public static BarcodeType classify(String barcode) {
    if (barcode.isEmpty() || !barcode.chars().allMatch(c -> c >= '0' && c <= '9')) {
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

  /** GTIN 이면 GS1 검사 숫자가 맞는가. GTIN 이 아니면(OTHER) 검사 대상이 아니므로 true. */
  public static boolean checkDigitValid(String barcode) {
    if (classify(barcode) == OTHER) {
      return true;
    }
    int last = barcode.charAt(barcode.length() - 1) - '0';
    return checkDigit(barcode.substring(0, barcode.length() - 1)) == last;
  }

  /**
   * GS1 검사 숫자(mod 10): 검사 숫자 바로 앞(오른쪽 끝)부터 가중치 3·1 을 번갈아 곱해 더하고, 10 의 배수까지 모자란 값. 8·12·13·14자리 모두
   * 같은 규칙이다.
   *
   * @param body 검사 숫자를 뺀 숫자열
   */
  public static int checkDigit(String body) {
    int sum = 0;
    for (int i = 0; i < body.length(); i++) {
      int d = body.charAt(body.length() - 1 - i) - '0';
      sum += i % 2 == 0 ? d * 3 : d;
    }
    return (10 - sum % 10) % 10;
  }
}
