package com.pmart.product.api;

import com.pmart.product.api.generated.BarcodesApi;
import com.pmart.product.api.generated.model.BarcodeResolution;
import com.pmart.product.application.Operator;
import com.pmart.product.application.ProductQueries;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 BarcodesApi — 활성 바코드 → SKU 해석(선행 0 보존, 연결 없으면 BARCODE_NOT_MAPPED). */
@RestController
public class BarcodesController implements BarcodesApi {

  private final ProductQueries queries;
  private final RegistrationMapping mapping;

  public BarcodesController(ProductQueries queries, RegistrationMapping mapping) {
    this.queries = queries;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<BarcodeResolution> resolveBarcode(String barcode) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    return ResponseEntity.ok(mapping.convert(queries.barcode(barcode), BarcodeResolution.class));
  }
}
