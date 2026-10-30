package com.pmart.delivery.capacity.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.capacity.domain.SlotRules;
import com.pmart.delivery.capacity.infrastructure.CenterCalendarClient;
import com.pmart.delivery.capacity.infrastructure.SlotHoldStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 예약 슬롯 조회. 운영 구간은 center-master(CUSTOMER_ORDER), 정원은 정책값(pmart.capacity.slot-capacity), 점유는 슬롯
 * 점유(만료 전 HELD + CONFIRMED). 잔여는 조회 사본이라 선택 성공을 보장하지 않는다(확보는 점유로만).
 */
@Service
public class SlotService {

  public record Slot(
      String slotId,
      SlotRules.Window window,
      @Nullable Integer capacity,
      int held,
      int confirmed,
      String availability,
      Instant cutoffAt) {

    public @Nullable Integer remaining() {
      return capacity == null ? null : Math.max(0, capacity - held - confirmed);
    }
  }

  public record SlotsView(
      String mfcId, LocalDate date, List<Slot> slots, int calendarVersion, Instant asOf) {}

  private static final SlotHoldStore.Occupancy NONE = new SlotHoldStore.Occupancy(0, 0);

  private final CenterCalendarClient calendars;
  private final SlotHoldStore holds;
  private final CapacityProperties props;
  private final Clock clock;

  public SlotService(
      CenterCalendarClient calendars, SlotHoldStore holds, CapacityProperties props, Clock clock) {
    this.calendars = calendars;
    this.holds = holds;
    this.props = props;
    this.clock = clock;
  }

  public SlotsView slots(String mfcId, LocalDate date) {
    Instant now = clock.instant();
    if (!SlotRules.bookableDate(date, now)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "예약 슬롯은 오늘·내일만 조회할 수 있습니다(Asia/Seoul).");
    }
    CenterCalendarClient.Day day = calendars.customerOrderDay(mfcId, date);
    List<Slot> slots = new ArrayList<>();
    if (day.open()) {
      Map<Integer, SlotHoldStore.Occupancy> used = holds.occupancy(mfcId, date, now);
      for (SlotRules.Window w : SlotRules.windows(date, day.ranges())) {
        Instant cutoff = w.startAt();
        SlotHoldStore.Occupancy o = used.getOrDefault(w.startMinute(), NONE);
        slots.add(
            new Slot(
                SlotRules.slotId(mfcId, w),
                w,
                props.slotCapacity(),
                o.held(),
                o.confirmed(),
                SlotRules.availability(props.slotCapacity(), o.held(), o.confirmed(), cutoff, now),
                cutoff));
      }
    }
    return new SlotsView(mfcId, date, slots, day.calendarVersion(), now);
  }
}
