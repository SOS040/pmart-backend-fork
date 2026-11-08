package com.pmart.mfc.location.domain;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 위치 코드 문법(MFC 문서 §6 Q-03 결정 2026-11-07, ADR-0042 — 계층형 고정 문법). 새 등록에만 검사한다(이미 등록된 코드는 바꿀 수 없으므로
 * 그대로 둔다).
 *
 * <ul>
 *   <li>선반·피킹(STORAGE·PICKING): {@code {온도대}-{구역}-{랙}-{단}-{칸}} — 예 {@code C-S1-03-2-04}. 온도대
 *       A(상온)·C(냉장)· F(냉동)는 위치의 temperatureZone 과 같아야 한다. 구역은 영문 대문자 1 + 숫자 1, 랙 2자리, 단 1~9, 칸 2자리.
 *       구역은 areaCode 와 같아야 하고, areaCode 가 없으면 코드의 구역을 쓴다.
 *   <li>그 밖의 구역: {@code {구역 접두}-{온도대}-{번호 2자리}} — 예 {@code RCV-C-01}. 접두 RCV 수령·PAK 포장·QRT 격리·DSP
 *       출고 대기·RTN 회수·DSL 폐기.
 * </ul>
 */
public final class LocationCodes {

  private LocationCodes() {}

  public static final String LOCATION_CODE_INVALID = "LOCATION_CODE_INVALID";

  private static final Pattern SHELF =
      Pattern.compile("^([ACF])-([A-Z][0-9])-([0-9]{2})-([1-9])-([0-9]{2})$");
  private static final Pattern OTHER = Pattern.compile("^([A-Z]{3})-([ACF])-([0-9]{2})$");

  private static final Map<String, String> TEMPERATURE =
      Map.of("AMBIENT", "A", "CHILLED", "C", "FROZEN", "F");

  private static final Map<String, String> PREFIX =
      Map.of(
          "RECEIVING", "RCV",
          "PACKING", "PAK",
          "QUARANTINE", "QRT",
          "DISPATCH_STAGING", "DSP",
          "RETURN", "RTN",
          "DISPOSAL", "DSL",
          "CART", "CRT");

  /** 검사 결과: 문제가 없으면 problem 이 비고 areaCode 는 확정값(선반·피킹은 코드의 구역). */
  public record Checked(Optional<String> problem, @Nullable String areaCode) {}

  public static Checked check(
      String code, String zone, String temperatureZone, @Nullable String areaCode) {
    String t = TEMPERATURE.get(temperatureZone);
    if (t == null) {
      return bad("온도대가 AMBIENT/CHILLED/FROZEN 이 아닙니다: " + temperatureZone, areaCode);
    }
    if ("STORAGE".equals(zone) || "PICKING".equals(zone)) {
      Matcher m = SHELF.matcher(code);
      if (!m.matches()) {
        return bad(
            "선반·피킹 위치 코드는 {온도대 A/C/F}-{구역 영문1+숫자1}-{랙 2자리}-{단 1~9}-{칸 2자리} 입니다(예 "
                + t
                + "-S1-03-2-04): "
                + code,
            areaCode);
      }
      if (!t.equals(m.group(1))) {
        return bad(
            "코드의 온도대 " + m.group(1) + " 가 위치 온도대 " + temperatureZone + "(" + t + ") 와 다릅니다.",
            areaCode);
      }
      if (areaCode != null && !areaCode.equals(m.group(2))) {
        return bad("코드의 구역 " + m.group(2) + " 가 areaCode " + areaCode + " 와 다릅니다.", areaCode);
      }
      return new Checked(Optional.empty(), m.group(2));
    }
    String prefix = PREFIX.get(zone);
    if (prefix == null) {
      return bad("알 수 없는 구역입니다: " + zone, areaCode);
    }
    Matcher m = OTHER.matcher(code);
    if (!m.matches() || !prefix.equals(m.group(1))) {
      return bad(
          zone
              + " 위치 코드는 "
              + prefix
              + "-{온도대 A/C/F}-{번호 2자리} 입니다(예 "
              + prefix
              + "-"
              + t
              + "-01): "
              + code,
          areaCode);
    }
    if (!t.equals(m.group(2))) {
      return bad(
          "코드의 온도대 " + m.group(2) + " 가 위치 온도대 " + temperatureZone + "(" + t + ") 와 다릅니다.",
          areaCode);
    }
    return new Checked(Optional.empty(), areaCode);
  }

  /** 새 문법(선반·그 밖) 모양의 코드인가. 옛 자유 형식 코드는 속성 변경 때 문법을 검사하지 않는다. */
  public static boolean followsGrammar(String code) {
    return SHELF.matcher(code).matches() || OTHER.matcher(code).matches();
  }

  private static Checked bad(String why, @Nullable String areaCode) {
    return new Checked(Optional.of(why), areaCode);
  }
}
