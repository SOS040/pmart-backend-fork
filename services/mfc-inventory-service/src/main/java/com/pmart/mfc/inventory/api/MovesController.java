package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.MovesApi;
import com.pmart.mfc.inventory.api.generated.model.CreateMoveAllocationRequest;
import com.pmart.mfc.inventory.api.generated.model.DropMoveRequest;
import com.pmart.mfc.inventory.api.generated.model.MoveAllocation;
import com.pmart.mfc.inventory.api.generated.model.PickUpMoveRequest;
import com.pmart.mfc.inventory.application.MoveService;
import com.pmart.mfc.inventory.infrastructure.MoveStore.MoveRow;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MovesApi — 재고 위치 이동(IV-03, 0.12.0). 업무 규칙은 {@link MoveService}. */
@RestController
public class MovesController implements MovesApi {

  private final MoveService service;

  public MovesController(MoveService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<MoveAllocation> createMoveAllocation(
      String idempotencyKey, String xSourceSystem, CreateMoveAllocationRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            dto(
                service.create(
                    idempotencyKey,
                    xSourceSystem,
                    new MoveService.CreateCommand(
                        b.getMoveId(),
                        b.getMfcId(),
                        b.getSku(),
                        b.getLotId(),
                        b.getFromLocation(),
                        b.getToLocation(),
                        b.getQty()))));
  }

  @Override
  public ResponseEntity<MoveAllocation> getMoveAllocation(String moveId) {
    return ResponseEntity.ok(dto(service.get(moveId)));
  }

  @Override
  public ResponseEntity<MoveAllocation> pickUpMove(
      String moveId, String idempotencyKey, String xSourceSystem, PickUpMoveRequest b) {
    return ResponseEntity.ok(
        dto(
            service.pickUp(
                idempotencyKey,
                xSourceSystem,
                moveId,
                b.getExecutionId(),
                b.getCartLocation(),
                b.getQty())));
  }

  @Override
  public ResponseEntity<MoveAllocation> dropMove(
      String moveId, String idempotencyKey, String xSourceSystem, DropMoveRequest b) {
    return ResponseEntity.ok(
        dto(
            service.drop(
                idempotencyKey,
                xSourceSystem,
                moveId,
                b.getExecutionId(),
                b.getToLocation(),
                b.getQty())));
  }

  @Override
  public ResponseEntity<MoveAllocation> cancelMove(
      String moveId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(dto(service.cancel(idempotencyKey, xSourceSystem, moveId)));
  }

  static MoveAllocation dto(MoveRow m) {
    var out = new MoveAllocation();
    out.setMoveId(m.moveId());
    out.setMfcId(m.mfcId());
    out.setSku(m.sku());
    out.setLotId(m.lotId());
    out.setFromLocation(m.fromLocation());
    out.setToLocation(m.toLocation());
    out.setCartLocation(m.cartLocation());
    out.setQty(m.qty());
    out.setPickedQty(m.pickedQty());
    out.setDroppedQty(m.droppedQty());
    out.setOnCartQty(m.onCart());
    out.setState(MoveAllocation.StateEnum.fromValue(m.state()));
    out.setSizeClass(m.sizeClass());
    out.setDestinationCapacity(m.destinationCapacity());
    out.setCreatedAt(m.createdAt().atOffset(ZoneOffset.UTC));
    out.setUpdatedAt(m.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
