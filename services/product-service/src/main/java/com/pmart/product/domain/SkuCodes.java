package com.pmart.product.domain;

import java.util.regex.Pattern;

/**
 * SKU 표기 규칙(Commerce 2.7 [확정], BO-03). 단품 {@code E-}, 밀봉 묶음 {@code B-} + E/B 가 함께 쓰는 전역 8자리 일련번호.
 * 번호는 재사용하지 않으며 E 와 B 가 같은 번호를 따로 받지 않는다(E-00000001 다음 B 는 B-00000002).
 */
public final class SkuCodes {

  public static final long MAX_SERIAL = 99_999_999L;
  private static final Pattern FORMAT = Pattern.compile("^[EB]-[0-9]{8}$");

  private SkuCodes() {}

  public static String format(String unitType, long serial) {
    if (!"E".equals(unitType) && !"B".equals(unitType)) {
      throw new IllegalArgumentException("unitType 은 E 또는 B: " + unitType);
    }
    if (serial < 1 || serial > MAX_SERIAL) {
      throw new IllegalStateException("SKU 일련번호 범위를 벗어났습니다: " + serial);
    }
    return unitType + "-" + String.format("%08d", serial);
  }

  public static boolean isValid(String sku) {
    return sku != null && FORMAT.matcher(sku).matches();
  }
}
