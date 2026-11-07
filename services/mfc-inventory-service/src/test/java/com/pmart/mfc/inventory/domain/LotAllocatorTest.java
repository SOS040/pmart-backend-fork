package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.domain.LotAllocator.Allocation;
import com.pmart.mfc.inventory.domain.LotAllocator.LineRequest;
import com.pmart.mfc.inventory.domain.LotAllocator.LotCandidate;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LOT 배정(ADR-0040 결정 4·5): FEFO, 줄당 LOT 수 제한 없음, 최소 잔여일(배송 약속일 기준). */
class LotAllocatorTest {

  static final String MILK = "E-00000011";
  static final String SNACK = "E-00000012";
  static final LocalDate D = LocalDate.of(2026, 11, 8);
  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  static LotCandidate lot(String sku, String id, LocalDate exp, int avail) {
    return new LotCandidate(sku, id, exp, T0, avail);
  }

  /** 냉장 우유 2일, 과자 30일. */
  static final LotAllocator.MinDays MIN = sku -> MILK.equals(sku) ? 2 : 30;

  @Test
  void fefoMixesLotsWithoutLimitOldestFirst() {
    var lots =
        List.of(
            lot(MILK, "LOT-C", D.plusDays(7), 10),
            lot(MILK, "LOT-A", D.plusDays(2), 2),
            lot(MILK, "LOT-B", D.plusDays(4), 1));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 4)), lots, D, MIN);
    assertThat(r.shortages()).isEmpty();
    assertThat(r.allocations())
        .containsExactly(
            new Allocation("L1", MILK, "LOT-A", 2),
            new Allocation("L1", MILK, "LOT-B", 1),
            new Allocation("L1", MILK, "LOT-C", 1));
  }

  @Test
  void lotsBelowMinimumRemainingDaysAreNotAllocated() {
    var lots =
        List.of(lot(MILK, "LOT-OLD", D.plusDays(1), 5), lot(MILK, "LOT-OK", D.plusDays(2), 1));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 2)), lots, D, MIN);
    assertThat(r.allocations()).isEmpty();
    assertThat(r.shortages())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.requestedQty()).isEqualTo(2);
              assertThat(s.eligibleQty()).isEqualTo(1);
            });
    // 예약 배송 날짜가 앞당겨지면(약속일 기준) 적격이 달라진다
    var earlier =
        LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 2)), lots, D.minusDays(1), MIN);
    assertThat(earlier.shortages()).isEmpty();
  }

  @Test
  void laterLinesSeeWhatEarlierLinesTookAndTiesUseFirstNormalReceipt() {
    var a = new LotCandidate(SNACK, "LOT-Z", D.plusDays(60), T0, 3);
    var b = new LotCandidate(SNACK, "LOT-Y", D.plusDays(60), T0.plusSeconds(60), 3);
    var r =
        LotAllocator.allocate(
            List.of(new LineRequest("L1", SNACK, 2), new LineRequest("L2", SNACK, 2)),
            List.of(b, a),
            D,
            MIN);
    assertThat(r.allocations())
        .containsExactly(
            new Allocation("L1", SNACK, "LOT-Z", 2),
            new Allocation("L2", SNACK, "LOT-Z", 1),
            new Allocation("L2", SNACK, "LOT-Y", 1));
  }

  @Test
  void lotWithoutExpiryIsNotCheckedAndGoesLast() {
    var noExp = new LotCandidate(SNACK, "LOT-NONE", null, T0, 5);
    var r =
        LotAllocator.allocate(
            List.of(new LineRequest(null, SNACK, 3)),
            List.of(noExp, lot(SNACK, "LOT-E", D.plusDays(40), 2)),
            D,
            sku -> null);
    // 최소 잔여일 미설정이면 기한 있는 LOT 은 배정 못 한다 — 호출자가 missingPolicy 로 먼저 CONFIG_REQUIRED
    assertThat(LotAllocator.missingPolicy(List.of(lot(SNACK, "LOT-E", D, 2)), sku -> null))
        .containsExactly(SNACK);
    assertThat(r.allocations()).containsExactly(new Allocation(null, SNACK, "LOT-NONE", 3));
  }

  @Test
  void unitKeyContributions() {
    var k = new Key("RCV-1", MILK, "LOT-A", Quality.PENDING_INSPECTION, Work.UNSTOWED);
    assertThat(new Change(k, 5).de()).isZero();
    assertThat(new Change(k.with(Quality.QUARANTINED, Work.UNSTOWED), 5).dq()).isEqualTo(5);
    assertThat(new Change(k.with(Quality.NORMAL, Work.UNSTOWED), 5).de()).isZero();
    assertThat(new Change(k.with(Quality.NORMAL, Work.STORED), 5).de()).isEqualTo(5);
    assertThat(new Change(k.with(Quality.NORMAL, Work.CANCEL_RETURN_PENDING), 5).de()).isZero();
  }
}
