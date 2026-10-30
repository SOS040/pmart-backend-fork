package com.pmart.mfc.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 재고 수량 규칙(MFC-business-logic IV-01·IV-05, TC-09). 원장은 센터·SKU 한 행이다(LOT·위치·품질 차원 없음).
 *
 * <ul>
 *   <li>불변식 0 ≤ R ≤ E ≤ H, A = E − R.
 *   <li>예약은 같은 SKU 행을 합산해 SKU 별 요청량 ≤ A 를 검증하고, 한 SKU 라도 부족하면 전체 거절(부분 확보 0).
 *   <li>수령: 정상 H+q·E+q(검수 단계 미구현), 격리 H+q·Q+q, 미판정 H+q.
 * </ul>
 */
public final class StockRules {

  public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
  public static final String SKU_NOT_HANDLED = "SKU_NOT_HANDLED";

  private StockRules() {}

  /** 센터·SKU 잔량. 행이 없으면 {@link #empty}. */
  public record Balance(
      String sku, int onHand, int quarantined, int sellable, int reserved, long version) {

    public static Balance empty(String sku) {
      return new Balance(sku, 0, 0, 0, 0, 0);
    }

    /** A = E − R. */
    public int available() {
      return sellable - reserved;
    }
  }

  /** 부족 행: SKU 별 합산 요청량과 그때의 A. */
  public record Shortage(String sku, int requestedQty, int availableQty) {}

  /** 요청 행 하나. */
  public record Line(String sku, int qty) {}

  /** 수령 행 하나(수량은 0 이상). */
  public record ReceiptLine(String sku, int normalQty, int quarantinedQty, int pendingQty) {}

  /** 수령으로 생기는 SKU 별 변화. */
  public record ReceiptDelta(String sku, int onHand, int quarantined, int sellable) {}

  /**
   * 같은 SKU 행을 합산한다. 잠금 순서(교착 방지)를 위해 SKU 오름차순.
   *
   * @throws IllegalArgumentException 수량이 1 미만
   */
  public static SortedMap<String, Integer> sumBySku(List<Line> lines) {
    SortedMap<String, Integer> out = new TreeMap<>();
    for (Line l : lines) {
      if (l.qty() < 1) {
        throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + l.sku());
      }
      out.merge(l.sku(), l.qty(), Math::addExact);
    }
    return out;
  }

  /**
   * SKU 별 요청량 ≤ A 검사.
   *
   * @param balances 잠근 잔량(없는 SKU 는 0)
   * @return 부족 행(SKU 오름차순). 비어 있으면 전부 확보 가능
   */
  public static List<Shortage> shortages(
      SortedMap<String, Integer> requested, Map<String, Balance> balances) {
    List<Shortage> out = new ArrayList<>();
    requested.forEach(
        (sku, qty) -> {
          Balance b = balances.getOrDefault(sku, Balance.empty(sku));
          if (qty > b.available()) {
            out.add(new Shortage(sku, qty, Math.max(0, b.available())));
          }
        });
    return out;
  }

  /** 수령 행을 SKU 별 변화로(SKU 오름차순). 같은 SKU 가 여러 행이면 합산. */
  public static List<ReceiptDelta> receiptDeltas(List<ReceiptLine> lines) {
    SortedMap<String, int[]> acc = new TreeMap<>();
    for (ReceiptLine l : lines) {
      if (l.normalQty() < 0 || l.quarantinedQty() < 0 || l.pendingQty() < 0) {
        throw new IllegalArgumentException("수량은 0 이상이어야 합니다: " + l.sku());
      }
      int[] a = acc.computeIfAbsent(l.sku(), k -> new int[3]);
      a[0] = Math.addExact(a[0], l.normalQty());
      a[1] = Math.addExact(a[1], l.quarantinedQty());
      a[2] = Math.addExact(a[2], l.pendingQty());
    }
    List<ReceiptDelta> out = new ArrayList<>();
    acc.forEach(
        (sku, a) -> {
          int onHand = Math.addExact(Math.addExact(a[0], a[1]), a[2]);
          if (onHand > 0) {
            out.add(new ReceiptDelta(sku, onHand, a[1], a[0]));
          }
        });
    return out;
  }

  /** 수령 내용의 지문(같은 receiptId 재전송이 같은 내용인지 판단). 수량과 대상만 본다 — 수령 시각·LOT 표기는 넣지 않는다. */
  public static String receiptFingerprint(
      String mfcId, @Nullable String trfId, @Nullable String departureId, List<ReceiptLine> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append(mfcId).append('|').append(trfId).append('|').append(departureId);
    lines.stream()
        .sorted(
            Comparator.comparing(ReceiptLine::sku)
                .thenComparingInt(ReceiptLine::normalQty)
                .thenComparingInt(ReceiptLine::quarantinedQty)
                .thenComparingInt(ReceiptLine::pendingQty))
        .forEach(
            l ->
                sb.append('|')
                    .append(l.sku())
                    .append(':')
                    .append(l.normalQty())
                    .append(':')
                    .append(l.quarantinedQty())
                    .append(':')
                    .append(l.pendingQty()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 해제 요청 행이 예약 전량과 같은지(부분 해제 미지원). 행이 없으면 전량.
   *
   * @param held 예약의 SKU 별 수량
   */
  public static boolean isFullRelease(@Nullable List<Line> requested, Map<String, Integer> held) {
    if (requested == null || requested.isEmpty()) {
      return true;
    }
    SortedMap<String, Integer> sum;
    try {
      sum = sumBySku(requested);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return sum.equals(new TreeMap<>(held));
  }
}
