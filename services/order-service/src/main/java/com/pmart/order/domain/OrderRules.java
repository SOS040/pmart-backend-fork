package com.pmart.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 주문서 금액·배송 규칙(Commerce 8장 [사용자 재확인]·[확정 정책]). 1차는 할인·쿠폰 없음 — 상품액 = 기본단가 × 수량 합.
 *
 * <ul>
 *   <li>배달팁: 한집 4,000 · 알뜰/예약 3,000. 상품액 30,000 이상 또는 첫 구매면 0(두 사유는 중복 차감 아님 — 30,000 이 먼저).
 *   <li>봉투값: 주문당 100 한 번(미사용 선택·분할 배송과 무관).
 *   <li>최소 주문: 상품액 15,000(배달팁·봉투값 제외). 부족액 = 15,000 − 상품액.
 *   <li>가격 동결 10분. 총 상품중량 10,000g 초과는 여러 배송으로 나눈다(추가 배달팁 없음). 단일 상품 10kg 초과는 나눌 수 없다.
 *   <li>[확정] 00:00~08:00 실제 배송 없음 — 한집·알뜰은 그 시간에 불가, 예약은 08시 이후 슬롯으로.
 * </ul>
 */
public final class OrderRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final long MIN_ORDER_AMOUNT = 15_000;
  public static final long FREE_DELIVERY_AMOUNT = 30_000;
  public static final long BAG_FEE = 100;
  public static final int MAX_WEIGHT_PER_DELIVERY_G = 10_000;
  public static final Duration QUOTE_TTL = Duration.ofMinutes(10);
  public static final int NIGHT_END_MINUTE = 8 * 60;

  public static final String SINGLE = "SINGLE";
  public static final String BUNDLED = "BUNDLED";
  public static final String SCHEDULED = "SCHEDULED";
  public static final List<String> MODES = List.of(SINGLE, BUNDLED, SCHEDULED);

  /** 차단 사유 표시 순서(주버튼은 첫 사유) — Commerce 10장: 최소금액 → 방식 → 예약시간 → 그 밖. */
  public static final List<String> BLOCKER_ORDER =
      List.of("MIN_ORDER_AMOUNT", "DELIVERY_MODE_UNAVAILABLE", "SLOT_REQUIRED", "SLOT_UNAVAILABLE");

  public record Tip(long base, long applied, String reason) {}

  public record Amounts(
      long itemAmount,
      Tip tip,
      long bagFee,
      long total,
      boolean minOrderSatisfied,
      long minOrderShortfall) {}

  public record Blocker(String code, @Nullable String detail) {}

  private OrderRules() {}

  public static long tipBase(String mode) {
    return SINGLE.equals(mode) ? 4_000 : 3_000;
  }

  /**
   * @param firstPurchaseExpected 첫 구매 무료 예상(EXPECTED) — 비회원·판정 불가면 false
   */
  public static Tip tip(String mode, long itemAmount, boolean firstPurchaseExpected) {
    long base = tipBase(mode);
    if (itemAmount >= FREE_DELIVERY_AMOUNT) {
      return new Tip(base, 0, "OVER_30000");
    }
    if (firstPurchaseExpected) {
      return new Tip(base, 0, "FIRST_PURCHASE");
    }
    return new Tip(base, base, "STANDARD");
  }

  public static Amounts amounts(String mode, long itemAmount, boolean firstPurchaseExpected) {
    Tip tip = tip(mode, itemAmount, firstPurchaseExpected);
    long shortfall = Math.max(0, MIN_ORDER_AMOUNT - itemAmount);
    return new Amounts(
        itemAmount, tip, BAG_FEE, itemAmount + tip.applied() + BAG_FEE, shortfall == 0, shortfall);
  }

  /**
   * 배송 수(10,000g 단위로 담기). 단위 중량이 10,000g 을 넘는 품목이 있으면 -1(나눌 수 없음).
   *
   * @param unitWeights 품목별 [단위 중량, 수량]
   */
  public static int plannedShipments(List<int[]> unitWeights) {
    List<Integer> units = new ArrayList<>();
    for (int[] w : unitWeights) {
      if (w[0] > MAX_WEIGHT_PER_DELIVERY_G) {
        return -1;
      }
      for (int i = 0; i < w[1]; i++) {
        units.add(w[0]);
      }
    }
    if (units.isEmpty()) {
      return 0;
    }
    // 큰 것부터 첫 맞는 배송에 담기(first-fit decreasing)
    units.sort(Comparator.reverseOrder());
    List<Integer> loads = new ArrayList<>();
    for (int u : units) {
      boolean placed = false;
      for (int i = 0; i < loads.size(); i++) {
        if (loads.get(i) + u <= MAX_WEIGHT_PER_DELIVERY_G) {
          loads.set(i, loads.get(i) + u);
          placed = true;
          break;
        }
      }
      if (!placed) {
        loads.add(u);
      }
    }
    return loads.size();
  }

  /** 00:00~08:00(Asia/Seoul)인가 — 한집·알뜰 불가 시간. */
  public static boolean nightTime(Instant now) {
    ZonedDateTime local = now.atZone(SEOUL);
    return local.getHour() * 60 + local.getMinute() < NIGHT_END_MINUTE;
  }

  /** 지금(분, Asia/Seoul)이 운영 구간 안인가. ranges = [시작분, 끝분). */
  public static boolean within(Instant now, List<int[]> ranges) {
    ZonedDateTime local = now.atZone(SEOUL);
    int minute = local.getHour() * 60 + local.getMinute();
    return ranges.stream().anyMatch(r -> r[0] <= minute && minute < r[1]);
  }

  public static List<Blocker> ordered(List<Blocker> blockers) {
    List<Blocker> out = new ArrayList<>(blockers);
    out.sort(Comparator.comparingInt(b -> BLOCKER_ORDER.indexOf(b.code())));
    return out;
  }

  public static Instant quoteExpiresAt(Instant createdAt) {
    return createdAt.plus(QUOTE_TTL);
  }

  /** "HH:mm" → 분(24:00 = 1440). */
  public static int minutes(String hhmm) {
    if ("24:00".equals(hhmm)) {
      return 1440;
    }
    String[] p = hhmm.split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }
}
