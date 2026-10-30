package com.pmart.delivery.capacity.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.capacity.domain.SlotRules;
import com.pmart.delivery.capacity.infrastructure.CenterCalendarClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 예약 슬롯 조회. 운영 구간은 center-master(CUSTOMER_ORDER), 정원은 정책값(pmart.capacity.slot-capacity).
 * 점유(held·confirmed)는 자원 확보 사가(슬롯 홀드)가 생기면 센다 — 지금은 0.
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

  private final CenterCalendarClient calendars;
  private final CapacityProperties props;
  private final Clock clock;

  public SlotService(CenterCalendarClient calendars, CapacityProperties props, Clock clock) {
    this.calendars = calendars;
    this.props = props;
    this.clock = clock;
  }

  public SlotsView slots(String mfcId, LocalDate date) {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, SlotRules.SEOUL);
    if (date.isBefore(today) || date.isAfter(today.plusDays(1))) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "예약 슬롯은 오늘·내일만 조회할 수 있습니다(Asia/Seoul).");
    }
    CenterCalendarClient.Day day = calendars.customerOrderDay(mfcId, date);
    List<Slot> slots = new ArrayList<>();
    if (day.open()) {
      for (SlotRules.Window w : SlotRules.windows(date, day.ranges())) {
        Instant cutoff = w.startAt();
        slots.add(
            new Slot(
                mfcId + ":" + date + ":" + w.start(),
                w,
                props.slotCapacity(),
                0,
                0,
                SlotRules.availability(props.slotCapacity(), 0, 0, cutoff, now),
                cutoff));
      }
    }
    return new SlotsView(mfcId, date, slots, day.calendarVersion(), now);
  }
}
