package com.pmart.product.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구매 상한(Commerce 2장 [확정]): 같은 기초상품의 Σ 수량 × 구성 낱개 수 ≤ 상한(기본 10). E 4개 + B 6입 1팩 = 10. 재고 단위 계산과
 * 다르다(B 를 E 로 바꾸지 않는다). 승인 예외는 아직 없다.
 */
public final class PurchaseLimit {

  public record Line(String sku, String baseProductId, int unitsPerSku, int quantity) {}

  public record Violation(
      String baseProductId, long totalUnits, int limitUnits, List<String> skus) {}

  private PurchaseLimit() {}

  public static List<Violation> check(List<Line> lines, int limitUnits) {
    Map<String, Long> totals = new LinkedHashMap<>();
    Map<String, List<String>> skus = new LinkedHashMap<>();
    for (Line l : lines) {
      totals.merge(
          l.baseProductId(),
          Math.multiplyExact((long) l.quantity(), l.unitsPerSku()),
          Math::addExact);
      List<String> list = skus.computeIfAbsent(l.baseProductId(), k -> new ArrayList<>());
      if (!list.contains(l.sku())) {
        list.add(l.sku());
      }
    }
    List<Violation> out = new ArrayList<>();
    totals.forEach(
        (base, total) -> {
          if (total > limitUnits) {
            out.add(new Violation(base, total, limitUnits, skus.get(base)));
          }
        });
    return out;
  }
}
