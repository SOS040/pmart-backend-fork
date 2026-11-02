package com.pmart.order.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.infrastructure.DraftStore;
import com.pmart.order.infrastructure.DraftStore.DraftRow;
import com.pmart.order.infrastructure.OrchestrationClient;
import com.pmart.order.infrastructure.Upstreams;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 결제 진입(checkouts, S1 시작). 초안의 가격 동결·견적 버전·차단 사유를 확인한 뒤 order-orchestration 에 사가를 시작시킨다. 진행(자원
 * 확보·결제창 진입 정보·실패)은 초안 조회 때 사가 사본으로 반영한다 — 이 서비스가 자원·결제 성공을 판정하지 않는다.
 */
@Service
public class CheckoutService {

  /** 결제 진입 결과(초안 조회에 덧붙인다). */
  public record Progress(
      String status,
      String nextAction,
      Map<String, String> resources,
      @Nullable Instant reservationExpiresAt,
      @Nullable JsonNode payment,
      @Nullable JsonNode failure) {}

  /**
   * 새 결제 진입을 만들지 않는 사가 상태. 진행 중뿐 아니라 확정(COMPLETED)·대사 필요(RECONCILIATION_REQUIRED, 승인됐지만 금액·상태 불일치)도
   * 같은 초안의 새 청구를 막는다.
   */
  private static final List<String> OPEN =
      List.of(
          "RUNNING",
          "WAITING_RESULT",
          "UNKNOWN_TRACKING",
          "COMPENSATING",
          "RECONCILIATION_REQUIRED",
          "COMPLETED");

  private final DraftService drafts;
  private final DraftStore store;
  private final OrchestrationClient orchestration;
  private final OrderService orders;
  private final Upstreams upstreams;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CheckoutService(
      DraftService drafts,
      DraftStore store,
      OrchestrationClient orchestration,
      OrderService orders,
      Upstreams upstreams,
      RequestIdempotency idempotency,
      Clock clock) {
    this.drafts = drafts;
    this.store = store;
    this.orchestration = orchestration;
    this.orders = orders;
    this.upstreams = upstreams;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** orderId. 같은 키·같은 본문 재전송은 같은 결과. 진행 중인 결제 진입이 있으면 그 주문 ID 를 그대로 준다. */
  public String start(
      String accountId,
      String draftId,
      String requestId,
      String sourceSystem,
      int acceptedQuoteVersion,
      @Nullable Integer expectedVersion) {
    Map<String, Object> request =
        Map.of("draftId", draftId, "acceptedQuoteVersion", acceptedQuoteVersion);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "startCheckout", accountId),
            request,
            String.class,
            () -> {
              DraftRow row =
                  store
                      .lock(draftId)
                      .filter(d -> d.accountId().equals(accountId))
                      .orElseThrow(() -> PmartException.notFound("DRAFT_NOT_FOUND", "주문서가 없습니다."));
              if (expectedVersion != null && expectedVersion != row.version()) {
                throw PmartException.versionConflict("주문서가 이미 바뀌었습니다. 다시 조회한 뒤 결제하세요.");
              }
              if (orders.confirmed(row.orderId())) {
                return row.orderId(); // 이미 확정된 주문 — 같은 초안으로 다시 청구하지 않는다
              }
              if (row.sagaId() != null && row.orderId() != null) {
                JsonNode saga = orchestration.saga(row.sagaId());
                if (saga == null || OPEN.contains(saga.path("state").asString())) {
                  return row.orderId(); // 진행 중(또는 확인 불가) — 새 사가를 만들지 않는다
                }
              }
              DraftService.View v = drafts.get(accountId, draftId);
              Instant now = clock.instant();
              if (v.expired() || v.snapshot().quote().quoteVersion() != acceptedQuoteVersion) {
                throw reject("RE_QUOTE_REQUIRED", "견적이 바뀌었거나 가격 동결 시간이 지났습니다. 주문서를 다시 만들어 주세요.");
              }
              if (!v.snapshot().blockers().isEmpty()) {
                throw reject(
                    "CHECKOUT_BLOCKED",
                    "결제할 수 없는 사유가 남아 있습니다: " + v.snapshot().blockers().getFirst().code());
              }
              // 계정 상태는 초안 이후 바뀔 수 있다(정지·탈퇴 진행) — 청구 직전에 한 번 더 본다(BR-37 질문 4, 사용자 결정 2026-11-02)
              if (!upstreams.purchasable(accountId)) {
                throw reject("ACCOUNT_NOT_PURCHASABLE", "지금 주문할 수 없는 계정 상태입니다.");
              }
              // 같은 진입 요청의 재시도가 같은 주문 ID·사가 키를 쓰도록 결정적으로 만든다
              String orderId =
                  "ORD-"
                      + UUID.nameUUIDFromBytes(
                          (draftId + ":" + requestId).getBytes(StandardCharsets.UTF_8));
              String sagaId =
                  orchestration.start(
                      "CHK-" + draftId + ":" + requestId, sagaBody(accountId, v, orderId));
              store.checkoutStarted(draftId, orderId, sagaId, requestId, now);
              return orderId;
            })
        .value();
  }

  /** 초안의 결제 진입 진행. 결제 진입 전이면 null. */
  public @Nullable Progress progress(DraftRow row, boolean draftExpired, boolean scheduled) {
    if (row.sagaId() == null) {
      return null;
    }
    // 확정 주문이 있으면 사가를 묻지 않는다(주문 원장이 정본)
    if (orders.confirmed(row.orderId())) {
      return confirmed(scheduled);
    }
    JsonNode saga = orchestration.saga(row.sagaId());
    if (saga == null) {
      Map<String, String> unknown = new LinkedHashMap<>();
      unknown.put("inventoryReservation", "UNKNOWN");
      unknown.put("capacityHold", "UNKNOWN");
      unknown.put("slotHold", scheduled ? "UNKNOWN" : "NOT_REQUIRED");
      unknown.put("payment", "UNKNOWN");
      return new Progress("RESOURCE_PENDING", "WAIT", unknown, null, null, null);
    }
    String state = saga.path("state").asString();
    String step = saga.path("currentStep").asString();
    Map<String, String> resources = resources(saga, scheduled);
    Instant expires = instant(saga.path("timers").path("reservationExpiresAt"));
    JsonNode failure = saga.hasNonNull("lastError") ? saga.get("lastError") : null;
    return switch (state) {
      case "RUNNING" -> new Progress("RESOURCE_PENDING", "WAIT", resources, expires, null, null);
      case "WAITING_RESULT" ->
          "AWAIT_PAYMENT_RESULT".equals(step) && saga.hasNonNull("payment")
              ? new Progress(
                  "PAYMENT_PENDING", "PAY", resources, expires, saga.get("payment"), null)
              : new Progress("PAYMENT_PENDING", "WAIT", resources, expires, null, null);
      case "UNKNOWN_TRACKING" ->
          new Progress("PAYMENT_UNKNOWN", "WAIT", resources, expires, null, null);
      case "COMPENSATING" ->
          new Progress("RESOURCE_PENDING", "WAIT", resources, null, null, failure);
      // 사가 완료 = 주문 확정까지 끝남(확정 주문 조회보다 사가 사본이 먼저 보일 수 있다)
      case "COMPLETED" -> confirmed(scheduled);
      // 승인됐지만 금액·상태 불일치 — 조사 중. 다시 결제하게 하지 않는다
      case "RECONCILIATION_REQUIRED" ->
          new Progress("PAYMENT_UNKNOWN", "WAIT", resources, null, null, null);
      default ->
          // 보상 종료: 이번 시도분은 해제됐다. 견적이 유효하면 다시 결제 진입할 수 있다
          draftExpired
              ? new Progress("EXPIRED", "RE_QUOTE", resources, null, null, failure)
              : new Progress("DRAFT", "REVIEW", resources, null, null, failure);
    };
  }

  /** 확정된 주문 — 자원은 확보 완료, 결제는 승인. */
  private static Progress confirmed(boolean scheduled) {
    Map<String, String> r = new LinkedHashMap<>();
    r.put("inventoryReservation", "HELD");
    r.put("capacityHold", "HELD");
    r.put("slotHold", scheduled ? "HELD" : "NOT_REQUIRED");
    r.put("payment", "APPROVED");
    return new Progress("CONFIRMED", "DONE", r, null, null, null);
  }

  /** 단계·보상 로그 → 계약 ResourceState 값. */
  private static Map<String, String> resources(JsonNode saga, boolean scheduled) {
    Map<String, String> released = new LinkedHashMap<>();
    for (JsonNode c : saga.path("compensationLog")) {
      if ("RELEASED".equals(c.path("releaseState").asString())) {
        released.put(c.path("resourceType").asString(), "RELEASED");
      }
    }
    Map<String, String> steps = new LinkedHashMap<>();
    for (JsonNode s : saga.path("steps")) {
      steps.put(s.path("step").asString(), s.path("state").asString());
    }
    Map<String, String> out = new LinkedHashMap<>();
    out.put(
        "inventoryReservation",
        resource(
            steps.get("RESERVE_INVENTORY"),
            released.get("INVENTORY_RESERVATION"),
            "NOT_REQUESTED"));
    out.put(
        "capacityHold",
        resource(steps.get("HOLD_CAPACITY"), released.get("CAPACITY_HOLD"), "NOT_REQUESTED"));
    out.put(
        "slotHold",
        scheduled
            ? resource(steps.get("HOLD_SLOT"), released.get("SLOT_HOLD"), "UNKNOWN")
            : "NOT_REQUIRED");
    String pay = steps.get("START_PAYMENT");
    out.put(
        "payment",
        "APPROVED".equals(saga.path("payment").path("state").asString(null))
            ? "APPROVED"
            : "SUCCEEDED".equals(pay)
                ? "STARTED"
                : "FAILED".equals(pay)
                    ? "FAILED"
                    : "UNKNOWN".equals(pay) ? "UNKNOWN" : "NOT_STARTED");
    return out;
  }

  private static String resource(@Nullable String step, @Nullable String released, String notYet) {
    if (released != null) {
      return "RELEASED";
    }
    if (step == null) {
      return notYet;
    }
    return switch (step) {
      case "SUCCEEDED" -> "HELD";
      case "FAILED" -> "FAILED";
      case "UNKNOWN", "IN_PROGRESS" -> "UNKNOWN";
      default -> notYet;
    };
  }

  private Map<String, Object> sagaBody(String accountId, DraftService.View v, String orderId) {
    DraftService.Snapshot s = v.snapshot();
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("draftId", v.row().draftId());
    b.put("orderId", orderId);
    b.put("purchaseIntentId", v.row().purchaseIntentId());
    b.put("customerRef", accountId);
    b.put("orderName", orderName(s));
    b.put("acceptedQuoteVersion", s.quote().quoteVersion());
    b.put("quoteExpiresAt", v.row().quoteExpiresAt().toString());
    b.put("mfcId", v.row().mfcId());
    b.put("deliveryMode", s.delivery().deliveryMode());
    if (s.delivery().arrivalWindow() != null) {
      var w = s.delivery().arrivalWindow();
      b.put(
          "arrivalWindow", Map.of("date", w.date().toString(), "start", w.start(), "end", w.end()));
    }
    b.put("amount", s.quote().totalAmount());
    b.put("currency", "KRW");
    b.put("paymentMethod", "TOSS_PAYMENTS");
    List<Map<String, Object>> lines = new ArrayList<>();
    int i = 1;
    int items = 0;
    for (DraftService.Line l : s.lines()) {
      lines.add(
          Map.of(
              "orderLineId",
              OrderRules.orderLineId(orderId, i++),
              "sku",
              l.sku(),
              "quantity",
              l.quantity()));
      items += l.quantity();
    }
    b.put("lines", lines);
    b.put(
        "demand",
        Map.of(
            "lineCount", lines.size(),
            "itemCount", items,
            "weightG", s.quote().weightG(),
            "plannedShipments", Math.max(1, s.quote().plannedShipments())));
    return b;
  }

  /** 결제창 주문명(BR-35): 첫 품목의 상품명 사본 + "외 N건". 사본이 없으면 품목 수로. */
  private static String orderName(DraftService.Snapshot s) {
    return OrderRules.orderName(s.lines().stream().map(DraftService.Line::productName).toList());
  }

  private static @Nullable Instant instant(JsonNode n) {
    String v = n.asString(null);
    return v == null || v.isBlank() ? null : java.time.OffsetDateTime.parse(v).toInstant();
  }

  private static PmartException reject(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
