package com.pmart.center.master.api;

import com.pmart.center.master.api.generated.InternalApi;
import com.pmart.center.master.api.generated.model.CalendarDay;
import com.pmart.center.master.api.generated.model.TimetableKind;
import com.pmart.center.master.application.CenterService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 서비스 토큰 캘린더 조회(capacity·order). 센터 변경 동기 반영(applyCenterChange)·결과 조회는 승인 사건 경로로만
 * 해서 아직 501.
 */
@RestController
public class InternalController implements InternalApi {

  private final CenterService centers;
  private final Clock clock;

  public InternalController(CenterService centers, Clock clock) {
    this.centers = centers;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<CalendarDay> getCenterCalendarInternal(
      String centerId, LocalDate date, TimetableKind kind) {
    return ResponseEntity.ok(
        CenterMapping.calendar(centers.calendar(centerId, date, kind.getValue()), clock.instant()));
  }
}
