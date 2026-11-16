package com.pmart.rider.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 운전면허 번호(2026-11-16 사용자 결정 — 라이더 앱 요청 §7): 오토바이·자동차·킥보드는 필수, 자전거·전기자전거·도보는 선택. 진위 확인 없이 중복만.
 * 12자리(2-2-6-2, 하이픈 선택).
 */
public final class LicenseRules {

  public static final Set<String> REQUIRED_FOR = Set.of("MOTORCYCLE", "CAR", "KICKBOARD");

  private LicenseRules() {}

  public static boolean required(String vehicleType) {
    return REQUIRED_FOR.contains(vehicleType);
  }

  /** 숫자 12자리로. 형식이 틀리면 null. */
  public static @Nullable String normalize(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String digits = raw.replace("-", "").strip();
    return digits.matches("[0-9]{12}") ? digits : null;
  }

  /** 표시용 — 지역 2자리·끝 2자리만(11-**-******-12). */
  public static String mask(String digits) {
    return digits.substring(0, 2) + "-**-******-" + digits.substring(10);
  }
}
