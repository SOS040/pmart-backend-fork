package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.SkusApi;
import com.pmart.cdc.inventory.api.generated.model.SkuProjection;
import com.pmart.cdc.inventory.api.generated.model.SkuProjectionLogistics;
import com.pmart.cdc.inventory.application.SkuProjector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SkusApi — 상품 SKU 투영 조회(서비스 간, /internal). CDC 는 승인된 물류 규격도 담는다(CDC-F-002). */
@RestController
public class SkusController implements SkusApi {

  private final SkuProjector projector;

  public SkusController(SkuProjector projector) {
    this.projector = projector;
  }

  @Override
  public ResponseEntity<SkuProjection> getSkuProjection(String sku) {
    com.pmart.cdc.inventory.domain.SkuProjection p = projector.projection(sku);
    var d = new SkuProjection();
    d.setSku(p.sku());
    d.setProductId(p.productId());
    d.setBaseProductId(p.baseProductId());
    d.setUnitType(p.unitType());
    d.setUnitsPerSku(p.unitsPerSku());
    d.setWeightG(p.weightG());
    d.setVolumeMl(p.volumeMl());
    d.setStorageType(p.storageType());
    d.setAlcohol(p.alcohol());
    if (!p.logistics().isEmpty()) {
      var lg = new SkuProjectionLogistics();
      lg.setUnitsPerBox(p.logistics().unitsPerBox());
      lg.setBoxesPerPallet(p.logistics().boxesPerPallet());
      lg.setTi(p.logistics().ti());
      lg.setHi(p.logistics().hi());
      d.setLogistics(lg);
    }
    d.setState(SkuProjection.StateEnum.fromValue(p.state().name()));
    d.setProductVersion(p.productVersion());
    d.setUpdatedAt(SkuHandlingsController.time(p.updatedAt()));
    return ResponseEntity.ok(d);
  }
}
