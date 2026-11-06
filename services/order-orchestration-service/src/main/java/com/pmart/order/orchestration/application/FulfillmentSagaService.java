package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.observability.CorrelationScope;
import com.pmart.order.orchestration.domain.FulfillmentRules;
import com.pmart.order.orchestration.domain.FulfillmentRules.AfterReject;
import com.pmart.order.orchestration.infrastructure.CancellationStore;
import com.pmart.order.orchestration.infrastructure.FulfillmentStore;
import com.pmart.order.orchestration.infrastructure.FulfillmentStore.AttemptRow;
import com.pmart.order.orchestration.infrastructure.FulfillmentStore.SagaRow;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * S2 이행 요청 사가(슬라이스 6a, docs/business/flows/order-checkout-saga.md §2·§2.0). 시작은
 * OrderConfirmed(order.order.v1) — 주문 하나에 사가 하나.
 *
 * <ol>
 *   <li>REQUEST_FULFILLMENT: mfc-fulfillment ConfirmOrder(작업 버전 n, 키 {@code
 *       sagaId:REQUEST_FULFILLMENT:v<n>}). 접수 → COMPLETED(WORK_ACCEPTED).
 *   <li>거절이 재시도 대상(재고 불일치·운영 중단)이면 RETRY_WAITING(AWAIT_RECOVERY): 1분마다 원래 재고 예약이 확정으로 작업 수량을 덮는지
 *       재확인 → 되찾으면 새 작업 버전. 대기는 처음 거절부터 W분 한 번만, 새 버전 최대 M번(센터 운영 설정 — 없으면 CONFIG_REQUIRED 보류, 자동
 *       취소 안 함). 배송 슬롯 시작이 지나면 기다리지 않는다. 운영 중단은 해제를 알 원천이 없어(6a) 재확인 때 바로 새 버전을 보내 fulfillment 가
 *       판정한다.
 *   <li>그 밖·재시도 끝 → REQUEST_SYSTEM_CANCEL: claim 시스템 자동 전체 취소(D6-4) → S3 가 중지·해제·환불.
 *       COMPLETED(CANCEL_REQUESTED).
 * </ol>
 *
 * 고객 취소(S3)가 먼저 시작됐으면 그 자리에서 닫는다(CLOSED_BY_CANCEL). 업무 거절(4xx)은 ESCALATED. 응답 불명은 같은 키로 다시
 * 보낸다(sweep).
 */
@Service
public class FulfillmentSagaService {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentSagaService.class);

  private final FulfillmentStore store;
  private final CancellationStore cancellations;
  private final Participants participants;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ObjectProvider<Tracer> tracer;

  public FulfillmentSagaService(
      FulfillmentStore store,
      CancellationStore cancellations,
      Participants participants,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      ObjectMapper mapper,
      Clock clock,
      ObjectProvider<Tracer> tracer) {
    this.store = store;
    this.cancellations = cancellations;
    this.participants = participants;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
    this.tracer = tracer;
  }

  // ---------- 시작 ----------

  /** order.order.v1 — OrderConfirmed 로 S2 시작(inbox 트랜잭션 안). 다른 주문 사건은 지나간다. 커밋 뒤 진행한다. */
  public void onOrderEvent(EventEnvelope e) {
    if (!"OrderConfirmed".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String orderId = p.path("orderId").asString();
    Instant after = props.fulfillment().startOrdersConfirmedAfter();
    if (after == null) {
      log.warn(
          "주문 {} 확정 — pmart.orchestration.fulfillment.start-orders-confirmed-after 미설정이라 S2 를 시작하지 않는다",
          orderId);
      return;
    }
    Instant confirmedAt = instant(p, "confirmedAt");
    if (confirmedAt == null || confirmedAt.isBefore(after)) {
      return; // 전환 시각 전 주문(토픽 처음부터 다시 읽을 때) — 이행 요청을 보내지 않는다
    }
    if (store.byOrder(orderId).isPresent()) {
      return; // 재전달
    }
    JsonNode window = p.path("arrivalWindow");
    Instant now = clock.instant();
    String sagaId = "SAGA-F-" + UUID.randomUUID();
    boolean created =
        store.insert(
            new SagaRow(
                sagaId,
                orderId,
                p.path("mfcId").asString(),
                p.path("reservationId").asString(),
                instant(window, "start"),
                request(p),
                "RUNNING",
                FulfillmentRules.REQUEST_FULFILLMENT,
                1,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                now,
                now,
                null));
    if (!created) {
      return;
    }
    log.info("이행 요청 사가 {} 시작 — 주문 {}", sagaId, orderId);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            kick(sagaId);
          }
        });
  }

  /**
   * ConfirmOrder 본문 사본(workVersion·sagaId 는 보낼 때 붙인다). 출고는 사건의 shipments(D6-2 주문당 1개), 없던 옛 사건이면 모든
   * 행으로 출고 1개. 행에 SKU·상품명·입수·중량을 붙인다.
   */
  private ObjectNode request(JsonNode p) {
    String orderId = p.path("orderId").asString();
    Map<String, JsonNode> lines = new LinkedHashMap<>();
    for (JsonNode l : p.path("lines")) {
      lines.put(l.path("orderLineId").asString(), l);
    }
    ObjectNode r = mapper.createObjectNode();
    r.put("orderId", orderId);
    if (p.has("orderVersion")) {
      r.put("orderVersion", p.path("orderVersion").asInt());
    }
    r.put("mfcId", p.path("mfcId").asString());
    r.put("reservationId", p.path("reservationId").asString());
    copy(p, r, "capacityHoldId");
    copy(p, r, "deliveryMode");
    copy(p, r, "outOfStockPreference");
    JsonNode w = p.path("arrivalWindow");
    if (w.has("start") && w.has("end")) {
      r.putObject("arrivalWindow")
          .put("start", w.path("start").asString())
          .put("end", w.path("end").asString());
    }
    List<JsonNode> shipments = new ArrayList<>();
    p.path("shipments").forEach(shipments::add);
    var out = r.putArray("shipments");
    if (shipments.isEmpty()) {
      ObjectNode s = out.addObject();
      s.put("shipmentId", orderId + "-S1");
      var ls = s.putArray("lines");
      lines.values().forEach(l -> line(ls.addObject(), l, l.path("qty").asInt()));
    } else {
      for (JsonNode sh : shipments) {
        ObjectNode s = out.addObject();
        s.put("shipmentId", sh.path("shipmentId").asString());
        if (sh.has("weightG")) {
          s.put("weightG", sh.path("weightG").asInt());
        }
        var ls = s.putArray("lines");
        for (JsonNode sl : sh.path("lines")) {
          JsonNode l = lines.get(sl.path("orderLineId").asString());
          ObjectNode o = ls.addObject();
          if (l == null) {
            o.put("orderLineId", sl.path("orderLineId").asString());
            o.put("qty", sl.path("qty").asInt());
          } else {
            line(o, l, sl.path("qty").asInt());
          }
        }
      }
    }
    return r;
  }

  private static void line(ObjectNode o, JsonNode l, int qty) {
    o.put("orderLineId", l.path("orderLineId").asString());
    o.put("sku", l.path("sku").asString());
    o.put("qty", qty);
    copy(l, o, "productName");
    if (l.has("unitsPerSku")) {
      o.put("unitsPerSku", l.path("unitsPerSku").asInt());
    }
    if (l.has("weightG")) {
      o.put("weightG", l.path("weightG").asInt());
    }
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException e) {
            log.warn("이행 요청 사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, e.toString());
          }
        });
  }

  /** 멈춘 사가 재개·재확인 시각이 된 대기·설정 대기 재평가. */
  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    Instant now = clock.instant();
    for (String sagaId :
        store.due(
            now, now.minus(props.stuckAfter()), now.minus(FulfillmentRules.RECHECK_INTERVAL))) {
      try {
        advance(sagaId);
      } catch (RuntimeException e) {
        log.warn("이행 요청 사가 {} 재개 실패: {}", sagaId, e.toString());
      }
    }
  }

  // ---------- 조회 ----------

  public Optional<SagaRow> find(String sagaId) {
    return store.find(sagaId);
  }

  public List<SagaRow> list(@Nullable String orderId, @Nullable String state, int size) {
    return store.list(orderId, state, size);
  }

  public List<AttemptRow> attempts(String sagaId) {
    return store.attempts(sagaId);
  }

  // ---------- 진행 ----------

  public void advance(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          SagaRow s = store.lock(sagaId).orElse(null);
          if (s == null) {
            return;
          }
          try (CorrelationScope c = CorrelationScope.open(tracer.getIfAvailable(), s.sagaId())) {
            switch (s.state()) {
              case "RUNNING" -> run(s);
              case "RETRY_WAITING", "CONFIG_REQUIRED" -> recheck(s);
              default -> {
                // 종료·상신
              }
            }
          }
        });
  }

  private void run(SagaRow s) {
    while (s != null && "RUNNING".equals(s.state())) {
      boolean moved =
          switch (s.currentStep()) {
            case FulfillmentRules.REQUEST_FULFILLMENT -> requestFulfillment(s);
            case FulfillmentRules.REQUEST_SYSTEM_CANCEL -> requestSystemCancel(s);
            default -> throw new IllegalStateException("알 수 없는 단계 " + s.currentStep());
          };
      if (!moved) {
        return;
      }
      s = store.find(s.sagaId()).orElse(null);
    }
  }

  /** 작업 요청. 다음 단계(RUNNING)로 넘어가면 true. */
  private boolean requestFulfillment(SagaRow s) {
    Instant now = clock.instant();
    if (closedByCancel(s, now)) {
      return false;
    }
    int v = s.workVersion();
    String requestId = FulfillmentRules.attemptRequestId(s.sagaId(), v);
    store.startAttempt(s.sagaId(), v, requestId, now);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.convertValue(s.request(), LinkedHashMap.class);
    body.put("workVersion", v);
    body.put("sagaId", s.sagaId());
    Result r = participants.confirmWork(requestId, body);
    return switch (r) {
      case Ok ok -> applyDecision(s, ok.body(), now);
      case Rejected rej -> {
        escalate(s, FulfillmentRules.REQUEST_FULFILLMENT, rej.code(), rej.message(), now);
        yield false;
      }
      case Participants.Unknown u -> {
        store.touch(s.sagaId(), now); // 실패로 단정하지 않는다 — 같은 키로 다시 보낸다(sweep)
        yield false;
      }
    };
  }

  private boolean applyDecision(SagaRow s, JsonNode d, Instant now) {
    String decision = d.path("decision").asString("");
    String fulfillmentId = d.path("fulfillmentId").asString(null);
    if (fulfillmentId != null) {
      store.setFulfillmentId(s.sagaId(), fulfillmentId);
    }
    if ("ACCEPTED".equals(decision)) {
      store.decideAttempt(s.sagaId(), s.workVersion(), "ACCEPTED", null, now);
      store.finish(
          s.sagaId(), "COMPLETED", "WORK_ACCEPTED", fulfillmentId, null, null, null, null, now);
      log.info("주문 {} 작업 접수(버전 {}) — 사가 {} 완료", s.orderId(), s.workVersion(), s.sagaId());
      return false;
    }
    if (!"REJECTED".equals(decision)) {
      escalate(
          s,
          FulfillmentRules.REQUEST_FULFILLMENT,
          "RESULT_CONFLICT",
          "작업 요청 응답에 결정이 없습니다: " + d,
          now);
      return false;
    }
    String reason = d.path("rejectReason").asString("UNKNOWN");
    store.decideAttempt(s.sagaId(), s.workVersion(), "REJECTED", reason, now);
    Instant first = s.firstRejectedAt() == null ? now : s.firstRejectedAt();
    log.info("주문 {} 작업 거절(버전 {}) {}", s.orderId(), s.workVersion(), reason);
    return afterReject(s, reason, first, now);
  }

  /** 대기·설정 대기 사가 재확인. */
  private void recheck(SagaRow s) {
    Instant now = clock.instant();
    if ("RETRY_WAITING".equals(s.state())
        && s.nextCheckAt() != null
        && s.nextCheckAt().isAfter(now)) {
      return;
    }
    if (closedByCancel(s, now)) {
      return;
    }
    String reason = lastReason(s);
    Instant first = s.firstRejectedAt() == null ? now : s.firstRejectedAt();
    AfterReject act = decide(s, reason, first, now);
    switch (act) {
      case CANCEL -> {
        toSystemCancel(s, first, now);
        run(store.find(s.sagaId()).orElseThrow());
      }
      case CONFIG_REQUIRED -> {
        if ("CONFIG_REQUIRED".equals(s.state())) {
          store.touch(s.sagaId(), now);
        } else {
          store.move(
              s.sagaId(),
              "CONFIG_REQUIRED",
              FulfillmentRules.AWAIT_RECOVERY,
              s.workVersion(),
              first,
              null,
              now);
        }
      }
      case WAIT -> {
        if (recovered(s, reason)) {
          store.move(
              s.sagaId(),
              "RUNNING",
              FulfillmentRules.REQUEST_FULFILLMENT,
              s.workVersion() + 1,
              first,
              null,
              now);
          log.info("주문 {} 재고 되찾음 — 작업 버전 {} 요청", s.orderId(), s.workVersion() + 1);
          run(store.find(s.sagaId()).orElseThrow());
        } else {
          store.move(
              s.sagaId(),
              "RETRY_WAITING",
              FulfillmentRules.AWAIT_RECOVERY,
              s.workVersion(),
              first,
              now.plus(FulfillmentRules.RECHECK_INTERVAL),
              now);
        }
      }
    }
  }

  /** 거절 직후: 대기·보류면 false, 자동 취소 단계로 넘어가면 true. */
  private boolean afterReject(SagaRow s, String reason, Instant first, Instant now) {
    AfterReject act = decide(s, reason, first, now);
    switch (act) {
      case WAIT -> {
        store.move(
            s.sagaId(),
            "RETRY_WAITING",
            FulfillmentRules.AWAIT_RECOVERY,
            s.workVersion(),
            first,
            now.plus(FulfillmentRules.RECHECK_INTERVAL),
            now);
        return false;
      }
      case CONFIG_REQUIRED -> {
        store.move(
            s.sagaId(),
            "CONFIG_REQUIRED",
            FulfillmentRules.AWAIT_RECOVERY,
            s.workVersion(),
            first,
            null,
            now);
        log.warn("주문 {} 센터 {} 재시도 설정(W·최대 횟수) 없음 — CONFIG_REQUIRED 보류", s.orderId(), s.mfcId());
        return false;
      }
      default -> {
        toSystemCancel(s, first, now);
        return true;
      }
    }
  }

  private AfterReject decide(SagaRow s, String reason, Instant first, Instant now) {
    OrchestrationProperties.Fulfillment f = props.fulfillment();
    Duration window = f.retryWindow(s.mfcId());
    Integer max = f.maxNewVersions(s.mfcId());
    return FulfillmentRules.afterReject(
        reason, now, s.slotStart(), first, window, max, s.workVersion());
  }

  /** 재고 불일치는 원래 예약 재확인, 운영 중단은 알 원천이 없어 다시 보낸다(fulfillment 가 판정). */
  private boolean recovered(SagaRow s, String reason) {
    if ("OPERATION_RESTRICTED".equals(reason)) {
      return true;
    }
    return FulfillmentRules.reservationRecovered(
        participants.reservation(s.reservationId()), s.request());
  }

  private void toSystemCancel(SagaRow s, Instant first, Instant now) {
    store.move(
        s.sagaId(),
        "RUNNING",
        FulfillmentRules.REQUEST_SYSTEM_CANCEL,
        s.workVersion(),
        first,
        null,
        now);
  }

  /** D6-4 자동 전체 취소 요청(claim). */
  private boolean requestSystemCancel(SagaRow s) {
    Instant now = clock.instant();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", s.orderId());
    body.put("reason", "FULFILLMENT_REJECTED");
    body.put("reasonDetail", lastReason(s) + " (작업 버전 " + s.workVersion() + ")");
    body.put("sourceRef", s.sagaId());
    Result r =
        participants.systemCancel(
            FulfillmentRules.requestId(s.sagaId(), FulfillmentRules.REQUEST_SYSTEM_CANCEL), body);
    switch (r) {
      case Ok ok -> {
        String claimId = ok.body().path("claimId").asString(null);
        store.finish(
            s.sagaId(), "COMPLETED", "CANCEL_REQUESTED", null, claimId, null, null, null, now);
        log.info("주문 {} 자동 전체 취소 요청 — 클레임 {}", s.orderId(), claimId);
      }
      case Rejected rej ->
          escalate(s, FulfillmentRules.REQUEST_SYSTEM_CANCEL, rej.code(), rej.message(), now);
      case Participants.Unknown u -> store.touch(s.sagaId(), now);
    }
    return false;
  }

  /** 고객 취소(S3)가 이미 시작됐으면 이 사가는 더 하지 않는다. */
  private boolean closedByCancel(SagaRow s, Instant now) {
    if (cancellations.byOrder(s.orderId()).isEmpty()) {
      return false;
    }
    store.finish(s.sagaId(), "COMPLETED", "CLOSED_BY_CANCEL", null, null, null, null, null, now);
    log.info("주문 {} 취소 사가가 있어 이행 요청 사가 {} 를 닫는다", s.orderId(), s.sagaId());
    return true;
  }

  private String lastReason(SagaRow s) {
    List<AttemptRow> a = store.attempts(s.sagaId());
    for (int i = a.size() - 1; i >= 0; i--) {
      if (a.get(i).rejectReason() != null) {
        return a.get(i).rejectReason();
      }
    }
    return "UNKNOWN";
  }

  private void escalate(SagaRow s, String step, String code, String message, Instant now) {
    store.finish(s.sagaId(), "ESCALATED", null, null, null, step, code, message, now);
    log.warn("이행 요청 사가 {} 상신 — {} {} {}", s.sagaId(), step, code, message);
  }

  private static void copy(JsonNode from, ObjectNode to, String field) {
    String v = from.path(field).asString(null);
    if (v != null && !v.isBlank()) {
      to.put(field, v);
    }
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }
}
