package com.pmart.product.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import com.pmart.product.infrastructure.CommandStore;
import com.pmart.product.infrastructure.CommandStore.IssuanceRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SKU 발급 확인 명령(/sku-issuances). SKU 는 ApprovalDecided(PRODUCT) 를 받을 때 이미 발급된다(이벤트 구동, 등록 설계 §2 ⑤).
 * 이 명령은 요청한 판매 단위가 그 승인으로 발급·갱신된 SKU 와 같은지 대조하고 결과를 requestId 로 묶는다. 새 번호를 만들지 않는다.
 */
@Service
public class SkuIssuanceService {

  public record Request(
      String approvalId,
      String baseProductId,
      @Nullable String productId,
      String unitType,
      int unitsPerSku,
      @Nullable Integer weightG) {}

  public record Result(
      String requestId, String approvalId, String sku, String productId, int productVersion) {}

  private final CatalogStore catalog;
  private final CommandStore commands;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SkuIssuanceService(
      CatalogStore catalog,
      CommandStore commands,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.catalog = catalog;
    this.commands = commands;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome<CommandResponse> issue(
      String requestId, Operator caller, Request in, Object body) {
    return idempotency.execute(
        caller.key(requestId, "issueSku"),
        body,
        CommandResponse.class,
        () -> {
          if (!catalog.approvalApplied(in.approvalId())) {
            throw new PmartException(
                ErrorCode.APPROVAL_REQUIRED,
                "승인 " + in.approvalId() + " 의 반영 기록이 없습니다. 승인 전이거나 아직 반영 중입니다.");
          }
          if (!catalog.baseProductExists(in.baseProductId())) {
            throw new PmartException(
                "BASE_PRODUCT_NOT_FOUND",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "기초상품 " + in.baseProductId() + " 가 없습니다.",
                false,
                List.of());
          }
          SkuRow sku =
              match(in)
                  .orElseThrow(
                      () ->
                          new PmartException(
                              "APPROVAL_SCOPE_MISMATCH",
                              HttpStatus.UNPROCESSABLE_CONTENT,
                              "이 승인으로 발급·갱신된 SKU 중 요청(기초상품·판매 단위·구성 낱개 수)과 같은 것이 없습니다.",
                              false,
                              List.of()));
          Instant at = clock.instant();
          if (commands.insertIssuance(
                  requestId, caller.sourceSystem(), in.approvalId(), sku.sku(), sku.productId(), at)
              == 0) {
            throw PmartException.idempotencyConflict("같은 requestId 가 이미 다른 요청에 쓰였습니다.");
          }
          int version =
              catalog.findProduct(sku.productId()).map(ProductRow::currentVersion).orElse(1);
          return new CommandResponse(
              CommandStatus.APPLIED, requestId, sku.sku(), null, version, false, null, at);
        });
  }

  public Result result(String requestId) {
    IssuanceRow r =
        commands
            .issuance(requestId)
            .orElseThrow(() -> PmartException.notFound("REQUEST_NOT_FOUND", "발급 요청이 없습니다."));
    int version = catalog.findProduct(r.productId()).map(ProductRow::currentVersion).orElse(1);
    return new Result(r.requestId(), r.approvalId(), r.sku(), r.productId(), version);
  }

  private Optional<SkuRow> match(Request in) {
    Set<String> skus = new LinkedHashSet<>();
    for (String json : commands.appliedSkusJson(in.approvalId())) {
      for (JsonNode s : mapper.readTree(json)) {
        skus.add(s.asString());
      }
    }
    return catalog.findSkus(List.copyOf(skus)).stream()
        .filter(s -> s.baseProductId().equals(in.baseProductId()))
        .filter(s -> s.unitType().equals(in.unitType()))
        .filter(s -> s.unitsPerSku() == in.unitsPerSku())
        .filter(s -> in.productId() == null || s.productId().equals(in.productId()))
        .filter(s -> in.weightG() == null || s.weightG() == in.weightG())
        .findFirst();
  }
}
