package com.pmart.product.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.product.api.generated.CommandsApi;
import com.pmart.product.api.generated.model.ChangeSaleState200Response;
import com.pmart.product.api.generated.model.ChangeSaleStateRequest;
import com.pmart.product.api.generated.model.GetSkuIssuance200Response;
import com.pmart.product.api.generated.model.SkuIssuanceRequest;
import com.pmart.product.application.Operator;
import com.pmart.product.application.SaleStateService;
import com.pmart.product.application.SkuIssuanceService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CommandsApi — 전사 판매 상태 변경, SKU 발급 확인. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class CommandsController implements CommandsApi {

  private final SaleStateService saleStates;
  private final SkuIssuanceService issuances;

  public CommandsController(SaleStateService saleStates, SkuIssuanceService issuances) {
    this.saleStates = saleStates;
    this.issuances = issuances;
  }

  @Override
  public ResponseEntity<ChangeSaleState200Response> changeSaleState(
      String productId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeSaleStateRequest body,
      @Nullable Integer xExpectedVersion) {
    CommandResponse r =
        saleStates
            .change(
                idempotencyKey,
                Operator.current(xSourceSystem),
                productId,
                xExpectedVersion,
                body.getSaleState().getValue(),
                body.getReason(),
                body.getApprovalId(),
                body)
            .value();
    return ResponseEntity.ok(response(r));
  }

  @Override
  public ResponseEntity<ChangeSaleState200Response> getSaleStateChange(
      String productId, String requestId) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    return ResponseEntity.ok(response(saleStates.result(productId, requestId)));
  }

  @Override
  public ResponseEntity<ChangeSaleState200Response> issueSku(
      String idempotencyKey, String xSourceSystem, SkuIssuanceRequest body) {
    CommandResponse r =
        issuances
            .issue(
                idempotencyKey,
                Operator.current(xSourceSystem),
                new SkuIssuanceService.Request(
                    body.getApprovalId(),
                    body.getBaseProductId(),
                    body.getProductId(),
                    body.getUnitType().getValue(),
                    body.getUnitsPerSku(),
                    body.getWeightG()),
                body)
            .value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response(r));
  }

  @Override
  public ResponseEntity<GetSkuIssuance200Response> getSkuIssuance(String requestId) {
    Operator.require(); // 조회도 운영자만(계약 [OPERATOR], 권한 빈틈 정리 2026-11-17)
    SkuIssuanceService.Result r = issuances.result(requestId);
    return ResponseEntity.ok(
        new GetSkuIssuance200Response()
            .requestId(r.requestId())
            .status(GetSkuIssuance200Response.StatusEnum.APPLIED)
            .sku(r.sku())
            .productId(r.productId())
            .productVersion(r.productVersion())
            .approvalId(r.approvalId()));
  }

  private static ChangeSaleState200Response response(CommandResponse r) {
    return new ChangeSaleState200Response()
        .status(ChangeSaleState200Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.resultId())
        .version(r.version())
        .retryable(r.retryable())
        .nextAction(r.nextAction())
        .occurredAt(OffsetDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC));
  }
}
