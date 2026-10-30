package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.QueriesApi;
import com.pmart.mfc.inventory.api.generated.model.Availability;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200Response;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf;
import com.pmart.mfc.inventory.application.SkuHandlingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 QueriesApi. 가용 조회는 취급 여부(center_sku_handling, 레코드 없음 = OFF)만 실제 값이다. 수량 원장은 슬라이스 4 에서 만들므로 그
 * 전에는 수량 필드를 비워 둔다(0 으로 채우지 않는다). 나머지 조회는 인터페이스 default 의 501.
 */
@RestController
public class QueriesController implements QueriesApi {

  private final SkuHandlingService handlings;

  public QueriesController(SkuHandlingService handlings) {
    this.handlings = handlings;
  }

  @Override
  public ResponseEntity<GetAvailability200Response> getAvailability(
      String mfcId, List<String> sku) {
    Map<String, Boolean> flags = handlings.enabledFlags(mfcId, sku.stream().distinct().toList());
    List<Availability> items = new ArrayList<>();
    flags.forEach(
        (s, enabled) -> {
          var a = new Availability();
          a.setSku(s);
          a.setHandlingEnabled(enabled);
          items.add(a);
        });
    var asOf = new GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(Instant.now()));
    var out = new GetAvailability200Response();
    out.setMfcId(mfcId);
    out.setAsOf(asOf);
    out.setItems(items);
    return ResponseEntity.ok(out);
  }
}
