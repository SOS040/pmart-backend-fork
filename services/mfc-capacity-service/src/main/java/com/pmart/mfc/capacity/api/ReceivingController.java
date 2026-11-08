package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.ReceivingApi;
import com.pmart.mfc.capacity.api.generated.model.GetReceivingCapabilities200Response;
import com.pmart.mfc.capacity.api.generated.model.GetReceivingCapabilities200ResponseAsOf;
import com.pmart.mfc.capacity.api.generated.model.GetUnloadingBayReservation200Response;
import com.pmart.mfc.capacity.api.generated.model.HoldCapacity201Response;
import com.pmart.mfc.capacity.api.generated.model.ReceivingWindow;
import com.pmart.mfc.capacity.api.generated.model.ReserveUnloadingBayRequest;
import com.pmart.mfc.capacity.application.ReceivingService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReceivingApi — 오전 입고 수령창·하차면 예약. 업무 규칙은 {@link ReceivingService}. */
@RestController
public class ReceivingController implements ReceivingApi {

  private final ReceivingService service;

  public ReceivingController(ReceivingService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<GetReceivingCapabilities200Response> getReceivingCapabilities(
      String mfcId, LocalDate date) {
    var w = service.window(mfcId, date);
    var win = new ReceivingWindow();
    win.setWindowId(mfcId + ":" + date);
    win.setStart(odt(w.start()));
    win.setEnd(odt(w.end()));
    win.setMaxLoadUnitsPerArrival(w.maxPerArrival());
    win.setTotalLoadUnits(w.totalUnits());
    win.setReservedLoadUnits(w.reservedUnits());
    win.setBayCount(w.bayCount());
    win.setState(ReceivingWindow.StateEnum.fromValue(w.state()));
    var out = new GetReceivingCapabilities200Response();
    out.setMfcId(mfcId);
    out.setDate(date);
    out.setVersion(1);
    var asOf = new GetReceivingCapabilities200ResponseAsOf();
    asOf.setAsOf(odt(Instant.now()));
    asOf.setFreshness(GetReceivingCapabilities200ResponseAsOf.FreshnessEnum.CURRENT);
    out.setAsOf(asOf);
    out.setWindows(List.of(win));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> reserveUnloadingBay(
      String idempotencyKey, String xSourceSystem, ReserveUnloadingBayRequest b) {
    var r =
        service.reserve(
            idempotencyKey,
            xSourceSystem,
            b.getMfcId(),
            b.getTripId(),
            b.getTrfIds(),
            b.getWindow(),
            b.getVehicleType(),
            b.getLoadUnits());
    return ResponseEntity.status(HttpStatus.CREATED).body(command(idempotencyKey, r));
  }

  @Override
  public ResponseEntity<GetUnloadingBayReservation200Response> getUnloadingBayReservation(
      String bayReservationId) {
    var r = service.get(bayReservationId);
    var out = new GetUnloadingBayReservation200Response();
    out.setBayReservationId(r.bayReservationId());
    out.setMfcId(r.mfcId());
    out.setTripId(r.tripId());
    out.setBayCode(r.bayCode());
    out.setWindow(r.windowStart() + "/" + r.windowEnd());
    out.setState(GetUnloadingBayReservation200Response.StateEnum.fromValue(r.state()));
    out.setVersion(r.version());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> cancelUnloadingBayReservation(
      String bayReservationId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(command(idempotencyKey, service.cancel(bayReservationId, xExpectedVersion)));
  }

  private static HoldCapacity201Response command(String requestId, ReceivingService.Bay r) {
    var out = new HoldCapacity201Response();
    out.setStatus(HoldCapacity201Response.StatusEnum.APPLIED);
    out.setRequestId(requestId);
    out.setResultId(r.bayReservationId());
    out.setAppliedQty(r.loadUnits());
    out.setVersion(r.version());
    out.setRetryable(false);
    out.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
    return out;
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
