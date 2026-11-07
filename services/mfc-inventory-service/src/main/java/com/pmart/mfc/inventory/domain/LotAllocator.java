package com.pmart.mfc.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 예약 LOT 배정(ADR-0040 결정 4·5, MFC 문서 §6 Q-05 결정 2026-11-07). 순수 계산.
 *
 * <ul>
 *   <li>적격: LOT 의 가용(E_lot − R_lot) &gt; 0. 소비기한이 있는 LOT 은 {@code 소비기한 − 배송 약속일 ≥ 최소 잔여일}. 소비기한 없는
 *       LOT(UNTRACKED 포함)은 소비기한 관리 대상이 아닌 것으로 본다(상품 정본에 관리 여부 필드가 생기기 전 임시 — ADR-0040 결정 5).
 *   <li>순서: 소비기한 빠른 순(없음은 맨 뒤) → 센터 최초 정상 인수 시각 → lot_id.
 *   <li>줄당 LOT 수 제한 없음. 줄은 요청 순서대로 배정하고, 앞 줄이 쓴 수량은 뒤 줄에서 빠진다.
 * </ul>
 */
public final class LotAllocator {

  private LotAllocator() {}

  /** 배정 후보 LOT. available = E_lot − R_lot. */
  public record LotCandidate(
      String sku,
      String lotId,
      @Nullable LocalDate expiryDate,
      @Nullable Instant firstNormalAt,
      int available) {}

  /** 요청 줄. */
  public record LineRequest(@Nullable String orderLineId, String sku, int qty) {}

  /** 배정 결과 조각. */
  public record Allocation(@Nullable String orderLineId, String sku, String lotId, int qty) {}

  /** 줄 부족: 요청량과 그 줄에 담을 수 있던 적격 수량. */
  public record LineShortage(
      @Nullable String orderLineId, String sku, int requestedQty, int eligibleQty) {}

  /** 결과. shortages 가 비어 있지 않으면 allocations 는 쓰지 않는다(전체 거절). */
  public record Result(List<Allocation> allocations, List<LineShortage> shortages) {}

  /** SKU 의 최소 잔여일. 소비기한 있는 LOT 을 판정할 때만 필요하다. null 이면 미설정. */
  @FunctionalInterface
  public interface MinDays {
    @Nullable Integer of(String sku);
  }

  /** 소비기한이 있는 LOT 이 있는데 최소 잔여일이 미설정인 SKU — 호출자가 CONFIG_REQUIRED 로 거절한다. */
  public static List<String> missingPolicy(List<LotCandidate> lots, MinDays minDays) {
    return lots.stream()
        .filter(l -> l.expiryDate() != null && l.available() > 0)
        .map(LotCandidate::sku)
        .distinct()
        .filter(s -> minDays.of(s) == null)
        .sorted()
        .toList();
  }

  public static boolean eligible(LotCandidate l, LocalDate promisedDate, MinDays minDays) {
    if (l.available() <= 0) {
      return false;
    }
    if (l.expiryDate() == null) {
      return true;
    }
    Integer min = minDays.of(l.sku());
    if (min == null) {
      return false;
    }
    return !l.expiryDate().isBefore(promisedDate.plusDays(min));
  }

  static final Comparator<LotCandidate> FEFO =
      Comparator.comparing(
              LotCandidate::expiryDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
          .thenComparing(
              LotCandidate::firstNormalAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
          .thenComparing(LotCandidate::lotId);

  public static Result allocate(
      List<LineRequest> lines, List<LotCandidate> lots, LocalDate promisedDate, MinDays minDays) {
    Map<String, List<LotCandidate>> bySku = new LinkedHashMap<>();
    for (LotCandidate l : lots) {
      if (eligible(l, promisedDate, minDays)) {
        bySku.computeIfAbsent(l.sku(), k -> new ArrayList<>()).add(l);
      }
    }
    bySku.values().forEach(v -> v.sort(FEFO));
    Map<String, Integer> used = new LinkedHashMap<>();
    List<Allocation> out = new ArrayList<>();
    List<LineShortage> shortages = new ArrayList<>();
    for (LineRequest line : lines) {
      List<LotCandidate> cands = bySku.getOrDefault(line.sku(), List.of());
      int need = line.qty();
      int eligibleQty = 0;
      List<Allocation> mine = new ArrayList<>();
      for (LotCandidate c : cands) {
        int left = c.available() - used.getOrDefault(c.lotId(), 0);
        if (left <= 0) {
          continue;
        }
        eligibleQty += left;
        if (need > 0) {
          int take = Math.min(need, left);
          mine.add(new Allocation(line.orderLineId(), line.sku(), c.lotId(), take));
          need -= take;
        }
      }
      if (need > 0) {
        shortages.add(new LineShortage(line.orderLineId(), line.sku(), line.qty(), eligibleQty));
        continue;
      }
      for (Allocation a : mine) {
        used.merge(a.lotId(), a.qty(), Integer::sum);
      }
      out.addAll(mine);
    }
    return new Result(shortages.isEmpty() ? out : List.of(), shortages);
  }
}
