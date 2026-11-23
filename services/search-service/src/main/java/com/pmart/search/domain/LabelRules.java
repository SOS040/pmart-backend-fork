package com.pmart.search.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 표시사항 값(products-v9, 계약 0.11.0 — 2026-11-23 사용자 "영양 넣어줘"·"알레르기는 필터 빼고 표시·검색만"). 원천은
 * product-content 0.6.0 {@code nutrition}(100g/100mL 기준)·{@code allergens}(함유·같은 시설). 값이 없으면 모름 —
 * 영양 필터에 걸리지 않을 뿐 "없음"으로 보지 않는다.
 */
public final class LabelRules {

  /** 영양 필터 키·이름·값(화면 순서). 같은 키 안의 값은 모두 만족(AND) — "저당 + 고단백". */
  public static final String NUTRITION_KEY = "nutrition";

  public static final String NUTRITION_LABEL = "영양";
  public static final String LOW_SUGAR = "저당";
  public static final String LOW_SODIUM = "저나트륨";
  public static final String HIGH_PROTEIN = "고단백";
  public static final List<String> NUTRITION_OPTIONS = List.of(LOW_SUGAR, LOW_SODIUM, HIGH_PROTEIN);

  /**
   * 기준값(정책값 — pmart-config {@code pmart.search.nutrition.*}, 식품 표시기준의 강조표시 기준을 따름). 하나라도 없으면 영양 필터는
   * CONFIG_REQUIRED.
   *
   * @param lowSugarG 저당: 100g 당 당류 미만(g)
   * @param lowSugarMl 저당: 100mL 당 당류 미만(g)
   * @param lowSodiumMg 저나트륨: 100g·100mL 당 나트륨 미만(mg)
   * @param highProteinG 고단백: 100g 당 단백질 이상(g)
   * @param highProteinMl 고단백: 100mL 당 단백질 이상(g)
   */
  public record Thresholds(
      double lowSugarG,
      double lowSugarMl,
      double lowSodiumMg,
      double highProteinG,
      double highProteinMl) {}

  /** 법정 알레르기 표시 이름과 흔한 다른 이름(검색어). */
  private static final Map<String, String> ALLERGEN_WORDS = new LinkedHashMap<>();

  static {
    for (String n :
        List.of(
            "알류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기", "복숭아", "토마토", "아황산류", "호두",
            "닭고기", "쇠고기", "오징어", "조개류", "잣")) {
      ALLERGEN_WORDS.put(n, n);
    }
    ALLERGEN_WORDS.put("계란", "알류");
    ALLERGEN_WORDS.put("달걀", "알류");
    ALLERGEN_WORDS.put("난류", "알류");
    ALLERGEN_WORDS.put("소고기", "쇠고기");
    ALLERGEN_WORDS.put("굴", "조개류");
    ALLERGEN_WORDS.put("전복", "조개류");
    ALLERGEN_WORDS.put("홍합", "조개류");
    ALLERGEN_WORDS.put("조개", "조개류");
    ALLERGEN_WORDS.put("아황산", "아황산류");
    ALLERGEN_WORDS.put("콩", "대두");
  }

  private static final Pattern ALLERGEN_QUERY =
      Pattern.compile("^(\\S+?)\\s*(함유|들어간|들어있는|알레르기|알러지)(\\s*(제품|상품))?$");

  private LabelRules() {}

  /**
   * 알레르기를 묻는 검색어("땅콩 함유", "계란 알레르기")면 법정 이름. 성분 이름만 친 검색어("우유")는 null — 우유를 사려는 검색에 우유가 든 과자를 섞지
   * 않는다.
   */
  public static @Nullable String allergenOf(@Nullable String query) {
    if (query == null) {
      return null;
    }
    Matcher m = ALLERGEN_QUERY.matcher(query.trim());
    return m.matches() ? ALLERGEN_WORDS.get(m.group(1)) : null;
  }

  /** 영양 필터 값 하나의 ES 조건. 모르는 값은 null(아무것도 맞지 않게 부르는 쪽이 처리). */
  public static @Nullable Map<String, Object> nutritionClause(String option, Thresholds t) {
    return switch (option) {
      case LOW_SUGAR -> perUnit("nutrition.sugarsG", "lt", t.lowSugarG(), t.lowSugarMl());
      case LOW_SODIUM -> range("nutrition.sodiumMg", "lt", t.lowSodiumMg());
      case HIGH_PROTEIN ->
          perUnit("nutrition.proteinG", "gte", t.highProteinG(), t.highProteinMl());
      default -> null;
    };
  }

  /** 선택한 영양 값 모두(AND). 모르는 값이 섞이면 아무것도 맞지 않는다. */
  public static Map<String, Object> nutritionFilter(List<String> options, Thresholds t) {
    List<Object> all = new ArrayList<>();
    for (String o : options) {
      Map<String, Object> c = nutritionClause(o, t);
      if (c == null) {
        return Map.of("match_none", Map.of());
      }
      all.add(c);
    }
    return Map.of("bool", Map.of("filter", all));
  }

  private static Map<String, Object> range(String field, String op, double v) {
    return Map.of("range", Map.of(field, Map.of(op, v)));
  }

  private static Map<String, Object> perUnit(String field, String op, double perG, double perMl) {
    return Map.of(
        "bool",
        Map.of(
            "should",
            List.of(
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(
                            Map.of("term", Map.of("nutrition.basisUnit", "G")),
                            range(field, op, perG)))),
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(
                            Map.of("term", Map.of("nutrition.basisUnit", "ML")),
                            range(field, op, perMl))))),
            "minimum_should_match",
            1));
  }
}
