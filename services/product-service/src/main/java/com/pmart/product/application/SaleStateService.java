package com.pmart.product.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.domain.SaleStateRules;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import com.pmart.product.infrastructure.CommandStore;
import com.pmart.product.infrastructure.CommandStore.GrantRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 전사 판매 상태 변경. MD 역할(설정값)만 바꾼다. 판매중지는 PRODUCT 승인(targetRef {@code SALE-STATE:{productId}})을 참조해야 하고
 * 승인 하나는 한 번만 쓴다. 반영 시 SKU 마다 ProductSaleStateChanged 를 발행한다.
 */
@Service
public class SaleStateService {

  /** 판매 상태 변경 승인의 targetRef 접두사. */
  public static final String TARGET_PREFIX = "SALE-STATE:";

  private final CatalogStore catalog;
  private final CommandStore commands;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final String changeRole;

  public SaleStateService(
      CatalogStore catalog,
      CommandStore commands,
      RequestIdempotency idempotency,
      EventPublisher events,
      ObjectMapper mapper,
      Clock clock,
      @Value("${pmart.product.sale-state-change-role:}") String changeRole) {
    this.catalog = catalog;
    this.commands = commands;
    this.idempotency = idempotency;
    this.events = events;
    this.mapper = mapper;
    this.clock = clock;
    this.changeRole = changeRole;
  }

  public Outcome<CommandResponse> change(
      String requestId,
      Operator operator,
      String productId,
      @Nullable Integer expectedVersion,
      String to,
      String reason,
      @Nullable String approvalId,
      Object body) {
    if (changeRole.isBlank()) {
      throw PmartException.configRequired("pmart.product.sale-state-change-role");
    }
    if (!operator.roles().contains(changeRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "판매 상태 변경 권한(" + changeRole + ")이 없습니다.");
    }
    return idempotency.execute(
        operator.key(requestId, "changeSaleState"),
        Map.of("productId", productId, "body", body),
        CommandResponse.class,
        () -> {
          ProductRow p =
              catalog
                  .findProduct(productId)
                  .orElseThrow(() -> PmartException.notFound("PRODUCT_NOT_FOUND", "상품이 없습니다."));
          if (expectedVersion != null && expectedVersion != p.rowVersion()) {
            throw PmartException.versionConflict(
                "상품이 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + p.rowVersion() + ").");
          }
          if (!SaleStateRules.ALL.contains(to)) {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, "판매 상태 값이 잘못되었습니다: " + to);
          }
          String usedApproval = null;
          if (SaleStateRules.requiresApproval(to)) {
            usedApproval = requireGrant(approvalId, productId, to, requestId);
          }
          Instant at = clock.instant();
          CommandResponse response;
          if (to.equals(p.saleState())) {
            // 이미 그 상태 — 바꾸지 않고 사건도 내지 않는다
            response =
                new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    productId,
                    null,
                    p.rowVersion(),
                    false,
                    null,
                    at);
          } else {
            if (!commands.updateSaleState(productId, p.rowVersion(), to, at)) {
              throw PmartException.versionConflict("상품이 동시에 바뀌었습니다. 다시 조회한 뒤 요청하세요.");
            }
            int newVersion = p.rowVersion() + 1;
            for (SkuRow s : catalog.skusOf(productId)) {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("sku", s.sku());
              payload.put("productId", productId);
              payload.put("state", SaleStateRules.eventState(to));
              payload.put("previousState", SaleStateRules.eventState(p.saleState()));
              payload.put("effectiveAt", at.toString());
              payload.put("reason", reason);
              payload.put("version", newVersion);
              events.publish(
                  "ProductSaleStateChanged",
                  1,
                  "Product",
                  productId,
                  newVersion,
                  new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator.id()),
                  payload);
            }
            response =
                new CommandResponse(
                    CommandStatus.APPLIED, requestId, productId, null, newVersion, false, null, at);
          }
          commands.insertChange(
              productId,
              requestId,
              p.saleState(),
              to,
              reason,
              usedApproval,
              operator.id(),
              mapper.writeValueAsString(response),
              at);
          return response;
        });
  }

  public CommandResponse result(String productId, String requestId) {
    return commands
        .changeResponse(productId, requestId)
        .map(json -> mapper.readValue(json, CommandResponse.class))
        .orElseThrow(() -> PmartException.notFound("REQUEST_NOT_FOUND", "판매 상태 변경 요청이 없습니다."));
  }

  private String requireGrant(
      @Nullable String approvalId, String productId, String to, String requestId) {
    if (approvalId == null || approvalId.isBlank()) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "판매중지는 PRODUCT 승인이 필요합니다.");
    }
    GrantRow g =
        commands
            .lockGrant(approvalId)
            .orElseThrow(
                () ->
                    new PmartException(
                        ErrorCode.APPROVAL_REQUIRED,
                        "승인 " + approvalId + " 를 아직 받지 못했습니다. 잠시 뒤 같은 키로 다시 보내세요."));
    if (!"APPROVED".equals(g.decision())) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "승인 " + approvalId + " 는 반려되었습니다.");
    }
    if (!g.productId().equals(productId) || !g.saleState().equals(to)) {
      throw new PmartException(
          "APPROVAL_SCOPE_MISMATCH",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "승인 범위(상품·판매 상태)와 요청이 다릅니다.",
          false,
          java.util.List.of());
    }
    if (g.consumedRequestId() != null) {
      throw PmartException.invalidState("승인 " + approvalId + " 는 이미 사용되었습니다.");
    }
    commands.consumeGrant(approvalId, requestId);
    return approvalId;
  }
}
