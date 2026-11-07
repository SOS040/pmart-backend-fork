package com.pmart.mfc.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 재고 수량 규칙(MFC-business-logic IV-01·IV-02·IV-05, TC-09). 센터 요약(Balance)과 요청 검증. 단위 행은 {@link
 * StockUnit}, LOT 배정은 {@link LotAllocator}(ADR-0040).
 *
 * <ul>
 *   <li>불변식 0 ≤ R ≤ E ≤ H, A = E − R.
 *   <li>예약은 같은 SKU 행을 합산해 SKU 별 요청량 ≤ A 를 먼저 검증하고, 한 SKU 라도 부족하면 전체 거절(부분 확보 0).
 *   <li>수령: 수령 위치에 UNSTOWED 로 — 정상·미판정 PENDING_INSPECTION, 격리 QUARANTINED. H 만 늘고 E 는 적치 뒤.
 * </ul>
 */
public final class StockRules {

  public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
  public static final String SKU_NOT_HANDLED = "SKU_NOT_HANDLED";

  private StockRules() {}

  /** 센터·SKU 요약. 행이 없으면 {@link #empty}. */
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

  /** 수령 행 하나(수량은 0 이상). sourceLotRef·expiryDate 가 둘 다 없으면 추적 미확인 LOT. */
  public record ReceiptLine(
      String sku,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable String locationCode,
      int normalQty,
      int quarantinedQty,
      int pendingQty) {

    public int total() {
      return Math.addExact(Math.addExact(normalQty, quarantinedQty), pendingQty);
    }
  }

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

  /**
   * 수령 행 검증: 수량 0 이상, 수령 위치 필수, 합계 1 이상.
   *
   * @return 합계
   * @throws IllegalArgumentException 위반
   */
  public static int validateReceipt(List<ReceiptLine> lines) {
    int total = 0;
    for (ReceiptLine l : lines) {
      if (l.normalQty() < 0 || l.quarantinedQty() < 0 || l.pendingQty() < 0) {
        throw new IllegalArgumentException("수량은 0 이상이어야 합니다: " + l.sku());
      }
      if (l.locationCode() == null || l.locationCode().isBlank()) {
        throw new IllegalArgumentException("toLocationCode(수령 위치)가 필요합니다: " + l.sku());
      }
      total = Math.addExact(total, l.total());
    }
    if (total == 0) {
      throw new IllegalArgumentException("수령 수량 합이 0 입니다.");
    }
    return total;
  }

  /** 수령 내용의 지문(같은 receiptId 재전송이 같은 내용인지 판단). 수령 시각은 넣지 않는다. 줄 순서를 보존한다(줄 번호). */
  public static String receiptFingerprint(
      String mfcId, @Nullable String trfId, @Nullable String departureId, List<ReceiptLine> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append(mfcId).append('|').append(trfId).append('|').append(departureId);
    for (ReceiptLine l : lines) {
      sb.append('|')
          .append(l.sku())
          .append(':')
          .append(l.sourceLotRef())
          .append(':')
          .append(l.expiryDate())
          .append(':')
          .append(l.locationCode())
          .append(':')
          .append(l.normalQty())
          .append(':')
          .append(l.quarantinedQty())
          .append(':')
          .append(l.pendingQty());
    }
    return sha256(sb.toString());
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
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
