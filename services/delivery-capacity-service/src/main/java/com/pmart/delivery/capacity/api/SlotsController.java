package com.pmart.delivery.capacity.api;

import com.pmart.delivery.capacity.api.generated.SlotsApi;
import com.pmart.delivery.capacity.api.generated.model.ArrivalWindow;
import com.pmart.delivery.capacity.api.generated.model.ListSlots200Response;
import com.pmart.delivery.capacity.api.generated.model.ListSlots200ResponseAsOf;
import com.pmart.delivery.capacity.api.generated.model.Slot;
import com.pmart.delivery.capacity.api.generated.model.SlotCapacity;
import com.pmart.delivery.capacity.application.SlotService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SlotsApi — 오늘·내일 예약 슬롯(정원·마감). 변환만 한다. */
@RestController
public class SlotsController implements SlotsApi {

  private final SlotService slots;

  public SlotsController(SlotService slots) {
    this.slots = slots;
  }

  @Override
  public ResponseEntity<ListSlots200Response> listSlots(String mfcId, LocalDate date) {
    SlotService.SlotsView v = slots.slots(mfcId, date);
    return ResponseEntity.ok(
        new ListSlots200Response()
            .mfcId(v.mfcId())
            .date(v.date())
            .slots(v.slots().stream().map(SlotsController::slot).toList())
            .asOf(
                new ListSlots200ResponseAsOf()
                    .asOf(OffsetDateTime.ofInstant(v.asOf(), ZoneOffset.UTC))
                    .sourceVersion(v.calendarVersion())
                    .freshness(ListSlots200ResponseAsOf.FreshnessEnum.CURRENT)));
  }

  private static Slot slot(SlotService.Slot s) {
    return new Slot()
        .slotId(s.slotId())
        .window(
            new ArrivalWindow()
                .date(s.window().date())
                .start(s.window().start())
                .end(s.window().end()))
        .capacity(
            s.capacity() == null
                ? new SlotCapacity().state(SlotCapacity.StateEnum.NOT_SET)
                : new SlotCapacity().state(SlotCapacity.StateEnum.VALUE).value(s.capacity()))
        .heldCount(s.held())
        .confirmedCount(s.confirmed())
        .remaining(s.remaining())
        .availability(Slot.AvailabilityEnum.fromValue(s.availability()))
        .cutoffAt(OffsetDateTime.ofInstant(s.cutoffAt(), ZoneOffset.UTC));
  }
}
