package com.pmart.product.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 표시사항 값(계약 0.6.0, 2026-11-23 — 검색 영양·알레르기). 표시사항에서 읽은 값만 둔다 — 모르는 칸은 null, 알레르기가 비어 있으면 "모름"이지
 * "없음"이 아니다. 저장은 초안·게시본의 {@code label_facts} jsonb 한 칸({@code {"nutrition":…,"allergens":…}}).
 */
public final class LabelFacts {

  /** 법정 알레르기 유발물질 표시 이름(식품등의 표시기준). 다른 이름(계란·굴 등)은 입력하는 쪽이 이 이름으로 맞춘다. */
  public static final Set<String> ALLERGEN_NAMES =
      Set.of(
          "알류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기", "복숭아", "토마토", "아황산류", "호두",
          "닭고기", "쇠고기", "오징어", "조개류", "잣");

  /** 100g(G) 또는 100mL(ML) 기준. */
  public record Nutrition(
      String basisUnit,
      @Nullable Double energyKcal,
      @Nullable Double sodiumMg,
      @Nullable Double sugarsG,
      @Nullable Double proteinG,
      @Nullable Double fatG) {}

  public record Allergens(
      @Nullable List<String> contains, @Nullable List<String> mayContain, @Nullable String text) {

    public List<String> containsOrEmpty() {
      return contains == null ? List.of() : contains;
    }

    public List<String> mayContainOrEmpty() {
      return mayContain == null ? List.of() : mayContain;
    }
  }

  /** 저장 모양. 둘 다 없으면 저장하지 않는다(null). */
  public record Stored(@Nullable Nutrition nutrition, @Nullable Allergens allergens) {}

  private LabelFacts() {}

  /** 입력 검사 — 걸린 것을 모두 돌려준다(비면 통과). */
  public static List<String> problems(@Nullable Nutrition n, @Nullable Allergens a) {
    List<String> out = new ArrayList<>();
    if (n != null) {
      if (!"G".equals(n.basisUnit()) && !"ML".equals(n.basisUnit())) {
        out.add("nutrition.basisUnit 은 G 또는 ML 이어야 합니다.");
      }
      for (Double v :
          new Double[] {n.energyKcal(), n.sodiumMg(), n.sugarsG(), n.proteinG(), n.fatG()}) {
        if (v != null && (v < 0 || v.isNaN() || v.isInfinite())) {
          out.add("nutrition 값은 0 이상이어야 합니다.");
          break;
        }
      }
      if (n.energyKcal() == null
          && n.sodiumMg() == null
          && n.sugarsG() == null
          && n.proteinG() == null
          && n.fatG() == null) {
        out.add("nutrition 에 값이 하나도 없습니다(모르면 nutrition 을 비우세요).");
      }
    }
    if (a != null) {
      for (String x : a.containsOrEmpty()) {
        if (!ALLERGEN_NAMES.contains(x)) {
          out.add("allergens.contains 의 '" + x + "' 은 법정 표시 이름이 아닙니다.");
        }
      }
      for (String x : a.mayContainOrEmpty()) {
        if (!ALLERGEN_NAMES.contains(x)) {
          out.add("allergens.mayContain 의 '" + x + "' 은 법정 표시 이름이 아닙니다.");
        }
        if (a.containsOrEmpty().contains(x)) {
          out.add("allergens '" + x + "' 이 함유와 같은 시설 제조에 함께 있습니다.");
        }
      }
      if (a.text() != null && a.text().length() > 1000) {
        out.add("allergens.text 는 1000자까지입니다.");
      }
    }
    return out;
  }
}
