package com.pmart.pricing.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import com.pmart.pricing.infrastructure.PriceStore.ApprovalLineRow;
import com.pmart.pricing.infrastructure.PriceStore.ApprovalRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 가격 버전 반영 명령(/price-version-applications). 가격 버전은 ApprovalDecided 를 받을 때 이미 만들어지므로(이벤트 구동), 이 명령은
 * 요청 내용이 승인 범위와 같은지 확인하고 그 반영 결과를 requestId 로 묶어 돌려준다. 승인을 아직 받지 못했으면 APPROVAL_REQUIRED(나중에 같은 키로
 * 다시 보내면 된다).
 */
@Service
public class PriceApplicationService {

  public record Item(String sku, long basePrice, Instant effectiveFrom) {}

  public record ResultItem(String sku, @Nullable Integer priceVersion, String state) {}

  public record Result(
      String requestId, CommandStatus status, String approvalId, List<ResultItem> items) {}

  private final PriceStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public PriceApplicationService(PriceStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public RequestIdempotency.Outcome<CommandResponse> apply(
      String requestId, String sourceSystem, String approvalId, List<Item> items, Object body) {
    PmartPrincipal caller =
        PmartPrincipal.current()
            .filter(
                p ->
                    p.type() == PmartPrincipal.PrincipalType.OPERATOR
                        || p.type() == PmartPrincipal.PrincipalType.SERVICE)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자·서비스만 할 수 있습니다."));
    return idempotency.execute(
        new RequestKey(sourceSystem, requestId, "applyPriceVersion", caller.subject()),
        body,
        CommandResponse.class,
        () -> {
          ApprovalRow approval =
              store
                  .approval(approvalId)
                  .orElseThrow(
                      () ->
                          new PmartException(
                              ErrorCode.APPROVAL_REQUIRED,
                              "승인 " + approvalId + " 를 아직 받지 못했습니다. 잠시 뒤 같은 키로 다시 보내세요."));
          if (!"APPROVED".equals(approval.decision())) {
            throw new PmartException(
                ErrorCode.APPROVAL_REQUIRED, "승인 " + approvalId + " 는 반려되었습니다.");
          }
          Map<String, ApprovalLineRow> lines =
              store.approvalLines(approvalId).stream()
                  .collect(Collectors.toMap(ApprovalLineRow::sku, Function.identity()));
          List<PmartException.Detail> mismatch = new ArrayList<>();
          int applied = 0;
          for (Item i : items) {
            ApprovalLineRow l = lines.get(i.sku());
            if (l == null
                || !Objects.equals(l.basePrice(), i.basePrice())
                || (l.requestedEffectiveFrom() != null
                    && !l.requestedEffectiveFrom().equals(i.effectiveFrom()))) {
              mismatch.add(new PmartException.Detail(i.sku(), "APPROVAL_SCOPE_MISMATCH"));
            } else if ("APPLIED".equals(l.state())) {
              applied++;
            }
          }
          if (!mismatch.isEmpty()) {
            throw new PmartException(
                "APPROVAL_SCOPE_MISMATCH",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "요청이 승인 범위(SKU·금액·효력 시각)와 다릅니다.",
                false,
                mismatch);
          }
          Instant at = clock.instant();
          if (store.insertApplication(requestId, sourceSystem, approvalId, at) == 0) {
            throw PmartException.idempotencyConflict("같은 requestId 가 이미 다른 요청에 쓰였습니다.");
          }
          CommandStatus status = applied == 0 ? CommandStatus.REJECTED : CommandStatus.APPLIED;
          String next = applied == items.size() ? null : "QUERY_RESULT";
          return new CommandResponse(status, requestId, approvalId, null, null, false, next, at);
        });
  }

  public Result result(String requestId) {
    String approvalId =
        store
            .applicationApproval(requestId)
            .orElseThrow(() -> PmartException.notFound("REQUEST_NOT_FOUND", "반영 요청이 없습니다."));
    ApprovalRow approval = store.approval(approvalId).orElseThrow();
    List<ResultItem> items = new ArrayList<>();
    for (ApprovalLineRow l : store.approvalLines(approvalId)) {
      if (l.priceVersion() == null) {
        items.add(new ResultItem(l.sku(), null, "REJECTED"));
        continue;
      }
      String state =
          store.versions(l.sku(), false).stream()
              .filter(v -> v.version() == l.priceVersion())
              .map(v -> v.state().name())
              .findFirst()
              .orElse(PriceVersion.State.SCHEDULED.name());
      items.add(new ResultItem(l.sku(), l.priceVersion(), state));
    }
    CommandStatus status =
        "REJECTED".equals(approval.result()) ? CommandStatus.REJECTED : CommandStatus.APPLIED;
    return new Result(requestId, status, approvalId, items);
  }
}
