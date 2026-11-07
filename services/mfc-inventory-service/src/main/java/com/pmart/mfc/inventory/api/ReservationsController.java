package com.pmart.mfc.inventory.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.api.generated.ReservationsApi;
import com.pmart.mfc.inventory.api.generated.model.ConfirmReservationRequest;
import com.pmart.mfc.inventory.api.generated.model.ReleaseReservationRequest;
import com.pmart.mfc.inventory.api.generated.model.Reservation;
import com.pmart.mfc.inventory.api.generated.model.ReservationPartState;
import com.pmart.mfc.inventory.api.generated.model.ReservationPartsInner;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventoryRequest;
import com.pmart.mfc.inventory.application.ReservationService;
import com.pmart.mfc.inventory.application.ReservationService.ReserveCommand;
import com.pmart.mfc.inventory.application.ReservationService.ReserveLine;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReservationsApi — 재고 예약·확정·해제·조회(서비스 간). 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class ReservationsController implements ReservationsApi {

  private final ReservationService service;

  public ReservationsController(ReservationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> reserveInventory(
      String idempotencyKey,
      String xSourceSystem,
      ReserveInventoryRequest body,
      @Nullable String traceparent) {
    List<ReserveLine> lines = new ArrayList<>();
    for (var l : body.getLines()) {
      if (l.getLotConstraint() != null) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "lotConstraint 는 아직 지원하지 않습니다(FEFO·최소 잔여일로 배정).");
      }
      lines.add(new ReserveLine(l.getOrderLineId(), l.getSku(), l.getQty()));
    }
    var r =
        service.reserve(
            idempotencyKey,
            xSourceSystem,
            new ReserveCommand(
                body.getOrderId(),
                body.getMfcId(),
                body.getTtlMinutes(),
                body.getPromisedDate(),
                lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  @Override
  public ResponseEntity<Reservation> getReservation(String reservationId) {
    var v = service.get(reservationId);
    var r = v.row();
    var out = new Reservation();
    out.setReservationId(r.reservationId());
    out.setOrderId(r.orderId());
    out.setMfcId(r.mfcId());
    out.setVersion(r.version());
    out.setState(ReservationPartState.fromValue(r.state().name()));
    out.setExpiresAt(r.expiresAt() == null ? null : SkuHandlingsController.time(r.expiresAt()));
    List<ReservationPartsInner> parts = new ArrayList<>();
    for (var part : v.parts()) {
      var p = new ReservationPartsInner();
      p.setPartId(part.partId());
      p.setOrderLineId(part.orderLineId());
      p.setSku(part.sku());
      p.setLotId(part.lotId());
      p.setKind(ReservationPartsInner.KindEnum.fromValue(part.kind()));
      p.setQty(part.qty());
      p.setState(ReservationPartState.fromValue(part.state().name()));
      p.setBlockedReason(part.blockedReason());
      parts.add(p);
    }
    out.setParts(parts);
    var asOf = new com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(Instant.now()));
    asOf.setSourceVersion(r.version());
    out.setAsOf(asOf);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> confirmReservation(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmReservationRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service.confirm(
            idempotencyKey,
            xSourceSystem,
            reservationId,
            body.getOrderId(),
            body.getPaymentAttemptId(),
            xExpectedVersion);
    return ResponseEntity.ok(Commands.dto(r));
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> releaseReservation(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseReservationRequest body,
      @Nullable Integer xExpectedVersion) {
    List<Line> lines =
        body.getLines() == null
            ? null
            : body.getLines().stream().map(l -> new Line(l.getSku(), l.getQty())).toList();
    var r =
        service.release(
            idempotencyKey,
            xSourceSystem,
            reservationId,
            body.getReason().getValue(),
            body.getSourceRef(),
            lines,
            xExpectedVersion);
    return ResponseEntity.ok(Commands.dto(r));
  }
}
