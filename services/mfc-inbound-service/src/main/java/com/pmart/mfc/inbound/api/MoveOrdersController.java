package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.MovesApi;
import com.pmart.mfc.inbound.api.generated.model.CancelMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.CreateMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.DropMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.ListMoveOrders200Response;
import com.pmart.mfc.inbound.api.generated.model.MoveOrder;
import com.pmart.mfc.inbound.api.generated.model.PickUpMoveOrderRequest;
import com.pmart.mfc.inbound.application.MoveOrderService;
import com.pmart.mfc.inbound.application.MoveOrderService.Row;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MovesApi — 재고 위치 이동 지시·실행(IV-03, 0.5.0). 업무 규칙은 {@link MoveOrderService}·mfc-inventory. */
@RestController
public class MoveOrdersController implements MovesApi {

  private final MoveOrderService service;

  public MoveOrdersController(MoveOrderService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<MoveOrder> createMoveOrder(
      String idempotencyKey, String xSourceSystem, CreateMoveOrderRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            dto(
                service.create(
                    idempotencyKey,
                    xSourceSystem,
                    new MoveOrderService.CreateCommand(
                        b.getMfcId(),
                        b.getSku(),
                        b.getLotId(),
                        b.getFromLocation(),
                        b.getToLocation(),
                        b.getQty(),
                        b.getReason()))));
  }

  @Override
  public ResponseEntity<ListMoveOrders200Response> listMoveOrders(
      String mfcId, @Nullable String state) {
    var out = new ListMoveOrders200Response();
    out.setItems(service.list(mfcId, state).stream().map(MoveOrdersController::dto).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<MoveOrder> getMoveOrder(String moveId) {
    return ResponseEntity.ok(dto(service.find(moveId)));
  }

  @Override
  public ResponseEntity<MoveOrder> pickUpMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, PickUpMoveOrderRequest b) {
    return ResponseEntity.ok(
        dto(service.pickUp(idempotencyKey, moveId, b.getCartLocation(), b.getQty())));
  }

  @Override
  public ResponseEntity<MoveOrder> dropMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, DropMoveOrderRequest b) {
    return ResponseEntity.ok(
        dto(service.drop(idempotencyKey, moveId, b.getToLocation(), b.getQty())));
  }

  @Override
  public ResponseEntity<MoveOrder> cancelMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, CancelMoveOrderRequest b) {
    return ResponseEntity.ok(dto(service.cancel(idempotencyKey, moveId, b.getReason())));
  }

  static MoveOrder dto(Row r) {
    var out = new MoveOrder();
    out.setMoveId(r.moveId());
    out.setMfcId(r.mfcId());
    out.setSku(r.sku());
    out.setLotId(r.lotId());
    out.setFromLocation(r.fromLocation());
    out.setToLocation(r.toLocation());
    out.setCartLocation(r.cartLocation());
    out.setQty(r.qty());
    out.setPickedQty(r.pickedQty());
    out.setDroppedQty(r.droppedQty());
    out.setOnCartQty(r.pickedQty() - r.droppedQty());
    out.setState(MoveOrder.StateEnum.fromValue(r.state()));
    out.setReason(r.reason());
    out.setCreatedBy(r.createdBy());
    out.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    out.setUpdatedAt(r.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
