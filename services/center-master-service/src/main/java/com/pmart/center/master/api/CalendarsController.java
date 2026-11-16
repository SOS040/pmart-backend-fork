package com.pmart.center.master.api;

import com.pmart.center.master.api.generated.CalendarsApi;
import com.pmart.center.master.api.generated.model.CalendarDay;
import com.pmart.center.master.api.generated.model.TimetableKind;
import com.pmart.center.master.application.Callers;
import com.pmart.center.master.application.CenterService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CalendarsApi — 영업 캘린더(그 날 영업 구간). 승인되지 않은 시간표 종류는 422 CONFIG_REQUIRED. */
@RestController
public class CalendarsController implements CalendarsApi {

  private final CenterService centers;
  private final Clock clock;

  public CalendarsController(CenterService centers, Clock clock) {
    this.centers = centers;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<CalendarDay> getCenterCalendar(
      String centerId, LocalDate date, TimetableKind kind) {
    Callers.requireOperator();
    return ResponseEntity.ok(
        CenterMapping.calendar(centers.calendar(centerId, date, kind.getValue()), clock.instant()));
  }
}
