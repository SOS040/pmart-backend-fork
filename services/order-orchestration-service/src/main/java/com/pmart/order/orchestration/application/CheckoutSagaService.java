package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.observability.CorrelationScope;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.domain.CheckoutRules;
import com.pmart.order.orchestration.domain.CheckoutRules.Window;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.SagaStore;
import com.pmart.order.orchestration.infrastructure.SagaStore.CompensationRow;
import com.pmart.order.orchestration.infrastructure.SagaStore.SagaRow;
import com.pmart.order.orchestration.infrastructure.SagaStore.StepRow;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * S1 결제 진입 사가(docs/business/flows/order-checkout-saga.md §1 a~f). 시작 명령은 사가만 저장하고 202 를 돌려준 뒤, 단계는
 * 비동기로 진행한다(동기 체인 2 hop 규칙).
 *
 * <ul>
 *   <li>단계 명령의 Idempotency-Key 는 {@code sagaId:단계} 로 고정 — 실행자가 죽거나 응답이 불명이어도 같은 키 재전송으로 정본에 한 번만
 *       반영된다. 멈춘 사가는 {@link #sweep()} 가 이어서 진행한다.
 *   <li>업무 거절(4xx)이면 이번 시도에서 새로 확보한 자원만 역순으로 해제하고(보상 로그), 원예약 조회로 해제를 확인한 뒤에만 RELEASED 로 기록한다. 해제가
 *       확인될 때까지 사가는 COMPENSATING.
 *   <li>결제 시작까지 성공하면 WAITING_RESULT. 자원 만료가 지나도록 승인이 없으면 결제 시도를 조회해 미승인 확정일 때만 해제한다. 불명·불일치면 해제하지
 *       않고 UNKNOWN_TRACKING(결제 확인 대기 점유).
 * </ul>
 */
@Service
public class CheckoutSagaService {

  private static final Logger log = LoggerFactory.getLogger(CheckoutSagaService.class);
  private static final String TOPIC = "order.saga.v1";

  /** 시작 명령 입력(계약 StartCheckoutSagaRequest 에서 변환). */
  public record Start(
      String draftId,
      String orderId,
      String purchaseIntentId,
      String customerRef,
      @Nullable String orderName,
      int acceptedQuoteVersion,
      Instant quoteExpiresAt,
      String mfcId,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      long amount,
      boolean containsAlcohol,
      List<Map<String, Object>> lines,
      Map<String, Object> demand) {}

  private final SagaStore store;
  private final Participants participants;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ObjectProvider<Tracer> tracer;

  public CheckoutSagaService(
      SagaStore store,
      Participants participants,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      ObjectMapper mapper,
      Clock clock,
      ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
    this.store = store;
    this.participants = participants;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 시작 ----------

  /** sagaId. 같은 키·같은 본문은 같은 사가. */
  public String start(String idempotencyKey, String sourceSystem, String actor, Start in) {
    Instant now = clock.instant();
    if (!now.isBefore(in.quoteExpiresAt())) {
      throw new PmartException(
          "RE_QUOTE_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "가격 동결 시간이 지났습니다. 다시 견적을 받으세요.",
          false,
          List.of());
    }
    if ("SCHEDULED".equals(in.deliveryMode())
        && (in.arrivalDate() == null || in.arrivalStart() == null || in.arrivalEnd() == null)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "예약배달은 도착 시간(arrivalWindow)이 필요합니다.");
    }
    String sagaId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "startCheckoutSaga", actor),
                in,
                String.class,
                () -> {
                  if (store.activeByDraft(in.draftId()).isPresent()) {
                    throw new PmartException(
                        "SAGA_ALREADY_ACTIVE",
                        HttpStatus.CONFLICT,
                        "이 초안의 결제 진입이 이미 진행 중입니다.",
                        false,
                        List.of());
                  }
                  String id = "SAGA-" + UUID.randomUUID();
                  store.insert(
                      new SagaRow(
                          id,
                          in.draftId(),
                          in.orderId(),
                          in.purchaseIntentId(),
                          idempotencyKey,
                          in.customerRef(),
                          in.mfcId(),
                          in.deliveryMode(),
                          in.arrivalDate(),
                          in.arrivalStart(),
                          in.arrivalEnd(),
                          in.amount(),
                          in.orderName(),
                          in.containsAlcohol(),
                          in.acceptedQuoteVersion(),
                          mapper.valueToTree(in.lines()),
                          mapper.valueToTree(in.demand()),
                          "RUNNING",
                          CheckoutRules.STEPS.getFirst(),
                          0,
                          in.quoteExpiresAt(),
                          null,
                          null,
                          null,
                          null,
                          null,
                          now,
                          now,
                          null));
                  // 사가 사건은 처음부터 correlationId = sagaId(작업 C)
                  try (CorrelationScope c = CorrelationScope.open(tracer.getIfAvailable(), id)) {
                    stateChanged(
                        id,
                        in.orderId(),
                        CheckoutRules.STEPS.getFirst(),
                        "RUNNING",
                        null,
                        null,
                        now);
                  }
                  return id;
                })
            .value();
    kick(sagaId);
    return sagaId;
  }

  /** 응답 뒤 비동기로 진행. 실패해도 sweep 이 잇는다. */
  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException e) {
            log.warn("사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, e.toString());
          }
        });
  }

  /** 멈춘 사가 재개·자원 만료 처리. */
  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    Instant now = clock.instant();
    for (String sagaId : store.due(now.minus(props.stuckAfter()), now)) {
      try {
        advance(sagaId);
      } catch (RuntimeException e) {
        log.warn("사가 {} 재개 실패: {}", sagaId, e.toString());
      }
    }
  }

  // ---------- 진행 ----------

  /**
   * 행 잠금 아래에서 끝까지(또는 기다릴 지점까지) 진행한다. 진입 경로(응답 뒤 kick·sweep·결제 사건)와 상관없이 이 구간의 correlationId =
   * sagaId(작업 C) — 참여자 호출의 X-Correlation-Id·baggage 와 발행 사건의 봉투가 같은 값을 잇는다.
   */
  public void advance(String sagaId) {
    try (CorrelationScope c = CorrelationScope.open(tracer.getIfAvailable(), sagaId)) {
      advanceLocked(sagaId);
    }
  }

  private void advanceLocked(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          SagaRow s = store.lock(sagaId).orElse(null);
          if (s == null) {
            return;
          }
          switch (s.state()) {
            case "RUNNING" -> run(s);
            case "COMPENSATING" -> compensate(s);
            case "WAITING_RESULT", "UNKNOWN_TRACKING" -> checkPayment(s);
            default -> {
              // 종료 상태
            }
          }
        });
  }

  private void run(SagaRow s) {
    store.ensureSteps(s.sagaId());
    for (StepRow step : store.steps(s.sagaId())) {
      if (List.of("SUCCEEDED", "SKIPPED").contains(step.state())) {
        continue;
      }
      s = store.find(s.sagaId()).orElseThrow();
      if (!"RUNNING".equals(s.state())) {
        return; // 앞 단계가 대기·보상·대사로 넘겼다
      }
      Instant now = clock.instant();
      if ("AWAIT_PAYMENT_RESULT".equals(step.step())) {
        return; // START_PAYMENT 에서 WAITING_RESULT 로 넘어간다. 승인은 checkPayment 가 반영
      }
      if (!CheckoutRules.PRE_PAYMENT_STEPS.contains(step.step())) {
        if (!afterApproval(s, step.step(), now)) {
          return;
        }
        continue;
      }
      if (!now.isBefore(s.quoteExpiresAt())) {
        // 가격 동결이 끝났다 — 불명 단계를 기다리지 않고 이번 시도분을 해제(불명 자원은 정본 TTL 로 소멸)
        fail(s, step.step(), "RE_QUOTE_REQUIRED", "가격 동결 시간이 지났습니다. 다시 견적을 받으세요.", now);
        return;
      }
      if (!execute(s, step.step(), now)) {
        return;
      }
    }
  }

  /** 한 단계. 다음 단계로 넘어가도 되면 true. */
  private boolean execute(SagaRow s, String step, Instant now) {
    String requestId = CheckoutRules.requestId(s.sagaId(), step);
    switch (step) {
      case "VALIDATE_QUOTE" -> {
        store.stepFinished(s.sagaId(), step, "SUCCEEDED", null, null, null, now);
        return true;
      }
      case "HOLD_SLOT" -> {
        if (!"SCHEDULED".equals(s.deliveryMode())) {
          store.stepFinished(s.sagaId(), step, "SKIPPED", null, null, null, now);
          return true;
        }
      }
      default -> {
        // 아래에서 정본 명령
      }
    }
    String target = target(step);
    store.stepStarted(s.sagaId(), step, target, requestId, now, s.quoteExpiresAt());
    Result r =
        switch (step) {
          case "RESERVE_INVENTORY" -> participants.reserveInventory(requestId, reserveBody(s));
          case "HOLD_CAPACITY" -> participants.holdCapacity(requestId, capacityBody(s, now));
          case "HOLD_SLOT" -> participants.holdSlot(requestId, slotBody(s));
          case "START_PAYMENT" -> participants.startPayment(requestId, paymentBody(s, now));
          default -> throw new IllegalStateException("알 수 없는 단계 " + step);
        };
    Instant done = clock.instant();
    return switch (r) {
      case Ok ok -> {
        store.stepFinished(s.sagaId(), step, "SUCCEEDED", ok.resultId(), null, null, done);
        if ("START_PAYMENT".equals(step)) {
          waitForPayment(s, ok, done);
          yield false;
        }
        String resourceType = CheckoutRules.resourceType(step);
        if (resourceType != null) {
          store.acquired(s.sagaId(), resourceType, ok.resultId(), target, done);
        }
        Instant expires =
            ok.expiresAt() != null
                ? ok.expiresAt()
                : now.plus(
                    CheckoutRules.HOLD_TTL_MINUTES, ChronoUnit.MINUTES); // 조회 실패 — TTL 로 보수 추정
        store.update(
            s.sagaId(),
            "RUNNING",
            next(step),
            CheckoutRules.earliest(s.resourceExpiresAt(), expires),
            null,
            null,
            null,
            null,
            done,
            false);
        yield true;
      }
      case Rejected rej -> {
        store.stepFinished(s.sagaId(), step, "FAILED", null, rej.code(), rej.message(), done);
        fail(s, step, rej.code(), rej.message(), done);
        yield false;
      }
      case Participants.Unknown u -> {
        // 실패로 단정하지 않는다 — 같은 키로 다시 보내 원결과를 받는다(sweep)
        store.stepFinished(s.sagaId(), step, "UNKNOWN", null, u.code(), u.message(), done);
        store.touch(s.sagaId(), done);
        yield false;
      }
    };
  }

  private void waitForPayment(SagaRow s, Ok ok, Instant now) {
    JsonNode b = ok.body();
    JsonNode c = b.path("checkout");
    Instant deadline = CheckoutRules.paymentDeadline(s.resourceExpiresAt(), s.quoteExpiresAt());
    ObjectNode payment = mapper.createObjectNode();
    payment.put("paymentAttemptId", ok.resultId());
    payment.put("amount", b.path("amount").asLong(s.amount()));
    if (s.orderName() != null) {
      payment.put("orderName", s.orderName());
    }
    payment.put("provider", c.path("provider").asString("TOSS_PAYMENTS"));
    putIfPresent(payment, "pgOrderId", c.path("pgOrderId").asString(null));
    putIfPresent(payment, "clientKey", c.path("clientKey").asString(null));
    putIfPresent(payment, "checkoutUrl", c.path("checkoutUrl").asString(null));
    Instant expiresAt =
        ok.expiresAt() != null && ok.expiresAt().isBefore(deadline) ? ok.expiresAt() : deadline;
    payment.put("expiresAt", expiresAt.toString());
    store.stepStarted(
        s.sagaId(), "AWAIT_PAYMENT_RESULT", Participants.PAYMENT, ok.resultId(), now, deadline);
    store.update(
        s.sagaId(),
        "WAITING_RESULT",
        "AWAIT_PAYMENT_RESULT",
        deadline,
        payment,
        null,
        null,
        null,
        now,
        false);
    stateChanged(
        s.sagaId(), s.orderId(), "AWAIT_PAYMENT_RESULT", "WAITING_RESULT", null, null, now);
  }

  /**
   * 결제 대기·불명 사가: 결제 시도 원결과를 조회해 반영한다(결제 사건은 이 조회를 앞당기는 신호일 뿐). 승인이면 대사·확정으로, 실패면 해제, 불명·불일치면 해제하지
   * 않는다. 미승인(CREATED·PENDING)은 자원 만료가 지날 때만 해제한다.
   */
  private void checkPayment(SagaRow s) {
    Instant now = clock.instant();
    String attemptId =
        s.payment() == null ? null : s.payment().path("paymentAttemptId").asString(null);
    JsonNode p = attemptId == null ? null : participants.payment(attemptId);
    if (attemptId != null && p == null) {
      store.touch(s.sagaId(), now); // 조회 실패 — 확인 전 해제 금지
      return;
    }
    String state = p == null ? null : p.path("state").asString(null);
    if ("MISMATCH".equals(state)) {
      reconciliation(s, "AWAIT_PAYMENT_RESULT", "AMOUNT", "결제 정본이 금액·주문 불일치를 보고했습니다.", now);
      return;
    }
    switch (CheckoutRules.verdict(state)) {
      case APPROVED -> {
        approved(s, Objects.requireNonNull(p), now);
        run(store.find(s.sagaId()).orElseThrow());
      }
      case UNRESOLVED -> {
        if (!"UNKNOWN_TRACKING".equals(s.state())) {
          store.stepFinished(
              s.sagaId(), "AWAIT_PAYMENT_RESULT", "UNKNOWN", attemptId, state, null, now);
          store.update(
              s.sagaId(),
              "UNKNOWN_TRACKING",
              "AWAIT_PAYMENT_RESULT",
              s.resourceExpiresAt(),
              s.payment(),
              null,
              null,
              null,
              now,
              false);
          stateChanged(
              s.sagaId(), s.orderId(), "AWAIT_PAYMENT_RESULT", "UNKNOWN_TRACKING", null, null, now);
        } else {
          store.touch(s.sagaId(), now);
        }
      }
      case NOT_APPROVED -> {
        if ("FAILED".equals(state)) {
          store.stepFinished(
              s.sagaId(), "AWAIT_PAYMENT_RESULT", "FAILED", attemptId, "PAYMENT_FAILED", null, now);
          fail(s, "AWAIT_PAYMENT_RESULT", "PAYMENT_FAILED", "결제가 승인되지 않았습니다.", now);
        } else if (s.resourceExpiresAt() != null && now.isBefore(s.resourceExpiresAt())) {
          store.touch(s.sagaId(), now); // 아직 결제창 유효 — 기다린다
        } else {
          store.stepFinished(
              s.sagaId(),
              "AWAIT_PAYMENT_RESULT",
              "TIMED_OUT",
              attemptId,
              "RESOURCE_EXPIRED",
              null,
              now);
          fail(
              s, "AWAIT_PAYMENT_RESULT", "RESOURCE_EXPIRED", "결제 가능 시간이 지났습니다. 다시 결제를 시작하세요.", now);
        }
      }
    }
  }

  /** 승인 사실 반영: 결제 사본에 승인액·PG 거래 ID, 보상 로그에 승인(보상 취소 대상), 대사 단계로. */
  private void approved(SagaRow s, JsonNode p, Instant now) {
    ObjectNode payment =
        s.payment() == null ? mapper.createObjectNode() : (ObjectNode) s.payment().deepCopy();
    payment.put("state", "APPROVED");
    payment.put("approvedAmount", p.path("approvedAmount").asLong(-1));
    putIfPresent(payment, "pgTransactionId", p.path("pgTransactionId").asString(null));
    putIfPresent(payment, "approvedCurrency", p.path("currency").asString(null));
    String attemptId = payment.path("paymentAttemptId").asString();
    store.stepFinished(s.sagaId(), "AWAIT_PAYMENT_RESULT", "SUCCEEDED", attemptId, null, null, now);
    store.acquired(s.sagaId(), "PAYMENT_APPROVAL", attemptId, Participants.PAYMENT, now);
    store.update(
        s.sagaId(), "RUNNING", "RECONCILE_PAYMENT", null, payment, null, null, null, now, false);
    stateChanged(s.sagaId(), s.orderId(), "RECONCILE_PAYMENT", "RUNNING", null, null, now);
  }

  /** 승인 뒤 단계(g~j). 다음 단계로 넘어가도 되면 true. 가격 동결과 무관하다(이미 승인된 금액). */
  private boolean afterApproval(SagaRow s, String step, Instant now) {
    JsonNode pay = Objects.requireNonNull(s.payment());
    String attemptId = pay.path("paymentAttemptId").asString();
    switch (step) {
      case "RECONCILE_PAYMENT" -> {
        long approvedAmount = pay.path("approvedAmount").asLong(-1);
        String currency = pay.path("approvedCurrency").asString("KRW");
        if (approvedAmount != s.amount() || !"KRW".equals(currency)) {
          store.stepFinished(s.sagaId(), step, "FAILED", attemptId, "AMOUNT_MISMATCH", null, now);
          reconciliation(
              s,
              step,
              "AMOUNT",
              "승인액 " + approvedAmount + " ≠ 청구액 " + s.amount() + " (" + currency + ")",
              now);
          return false;
        }
        store.stepFinished(s.sagaId(), step, "SUCCEEDED", attemptId, null, null, now);
        store.update(
            s.sagaId(), "RUNNING", "CONFIRM_RESOURCES", null, pay, null, null, null, now, false);
        return true;
      }
      case "CONFIRM_RESOURCES" -> {
        store.stepStarted(
            s.sagaId(), step, "mfc-inventory/mfc-capacity/delivery-capacity", null, now, null);
        for (CompensationRow c : store.compensations(s.sagaId())) {
          if ("PAYMENT_APPROVAL".equals(c.resourceType())) {
            continue;
          }
          String requestId = CheckoutRules.confirmRequestId(s.sagaId(), c.resourceType());
          Result r =
              participants.confirm(
                  c.resourceType(), c.resourceId(), requestId, s.sagaId(), s.orderId(), attemptId);
          Instant done = clock.instant();
          if (r instanceof Rejected rej) {
            // 점유가 만료·해제됐다(지연 승인) — 승인을 보상 취소하고 나머지를 해제한다
            store.stepFinished(s.sagaId(), step, "FAILED", null, rej.code(), rej.message(), done);
            fail(s, step, "RESERVATION_LOST", c.resourceType() + ": " + rej.message(), done);
            return false;
          }
          if (r instanceof Participants.Unknown u) {
            store.stepFinished(s.sagaId(), step, "UNKNOWN", null, u.code(), u.message(), done);
            store.touch(s.sagaId(), done);
            return false;
          }
        }
        Instant done = clock.instant();
        store.stepFinished(s.sagaId(), step, "SUCCEEDED", null, null, null, done);
        store.update(
            s.sagaId(), "RUNNING", "CONFIRM_ORDER", null, pay, null, null, null, done, false);
        return true;
      }
      case "CONFIRM_ORDER" -> {
        String requestId = CheckoutRules.requestId(s.sagaId(), step);
        store.stepStarted(s.sagaId(), step, Participants.ORDER, requestId, now, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentAttemptId", attemptId);
        body.put("pgTransactionId", pay.path("pgTransactionId").asString(""));
        body.put("approvedAmount", pay.path("approvedAmount").asLong());
        for (CompensationRow c : store.compensations(s.sagaId())) {
          switch (c.resourceType()) {
            case "INVENTORY_RESERVATION" -> body.put("reservationId", c.resourceId());
            case "CAPACITY_HOLD" -> body.put("capacityHoldId", c.resourceId());
            case "SLOT_HOLD" -> body.put("slotHoldId", c.resourceId());
            default -> {
              // 결제 승인은 위에서
            }
          }
        }
        Result r = participants.confirmOrder(s.orderId(), requestId, body);
        Instant done = clock.instant();
        return switch (r) {
          case Ok ok -> {
            store.stepFinished(s.sagaId(), step, "SUCCEEDED", s.orderId(), null, null, done);
            store.update(s.sagaId(), "COMPLETED", step, null, pay, null, null, null, done, true);
            stateChanged(s.sagaId(), s.orderId(), step, "COMPLETED", null, null, done);
            yield false;
          }
          case Rejected rej -> {
            store.stepFinished(s.sagaId(), step, "FAILED", null, rej.code(), rej.message(), done);
            if ("AMOUNT_MISMATCH".equals(rej.code())) {
              reconciliation(s, step, "AMOUNT", rej.message(), done);
            } else {
              fail(s, step, rej.code(), rej.message(), done);
            }
            yield false;
          }
          case Participants.Unknown u -> {
            store.stepFinished(s.sagaId(), step, "UNKNOWN", null, u.code(), u.message(), done);
            store.touch(s.sagaId(), done);
            yield false;
          }
        };
      }
      default -> throw new IllegalStateException("알 수 없는 단계 " + step);
    }
  }

  /** 대사 불일치 — 확정·출고·자동 보상 모두 멈추고 사건으로 넘긴다(강제 출고·강제 취소 금지). */
  private void reconciliation(SagaRow s, String step, String type, String details, Instant now) {
    store.update(
        s.sagaId(),
        "RECONCILIATION_REQUIRED",
        step,
        s.resourceExpiresAt(),
        s.payment(),
        step,
        "RECONCILIATION_REQUIRED",
        details,
        now,
        false);
    stateChanged(
        s.sagaId(),
        s.orderId(),
        step,
        "RECONCILIATION_REQUIRED",
        "RECONCILIATION_REQUIRED",
        details,
        now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sagaId", s.sagaId());
    payload.put("orderId", s.orderId());
    if (s.payment() != null) {
      payload.put("paymentAttemptId", s.payment().path("paymentAttemptId").asString());
    }
    payload.put("mismatchType", type);
    payload.put("details", Map.of("message", details));
    payload.put("detectedAt", now.toString());
    publish("ReconciliationRequired", s.sagaId(), payload);
  }

  /** 결제 사건(order.payment.v1) — inbox 트랜잭션 안. 원결과는 조회로 확인하므로 커밋 뒤 사가를 깨우기만 한다. */
  public void onPaymentEvent(EventEnvelope e) {
    if (!List.of(
            "PaymentApproved",
            "PaymentFailed",
            "PaymentResultUnknown",
            "PaymentMismatch",
            "CompensationCancelResult")
        .contains(e.eventType())) {
      return;
    }
    String attemptId = e.payload().path("paymentAttemptId").asString(null);
    if (attemptId == null) {
      return;
    }
    store
        .sagaIdByPaymentAttempt(attemptId)
        .ifPresent(
            sagaId ->
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        kick(sagaId);
                      }
                    }));
  }

  // ---------- 보상 ----------

  private void fail(SagaRow s, String step, String code, String message, Instant now) {
    store.update(
        s.sagaId(),
        "COMPENSATING",
        step,
        s.resourceExpiresAt(),
        s.payment(),
        step,
        code,
        message,
        now,
        false);
    stateChanged(s.sagaId(), s.orderId(), step, "COMPENSATING", code, message, now);
    compensate(store.find(s.sagaId()).orElseThrow());
  }

  /**
   * 이번 시도분 자원만 역순(확보 늦은 것부터) 해제. 모두 확인되면 COMPENSATED. 승인된 결제가 있으면 보상 취소(전액)가 CANCELLED 로 확인될 때까지
   * 재고·여력·슬롯을 풀지 않는다(승인취소 성공 전 재판매 금지, 흐름 §1.2).
   */
  private void compensate(SagaRow s) {
    boolean allReleased = true;
    for (CompensationRow c : store.compensations(s.sagaId())) {
      if ("RELEASED".equals(c.releaseState())) {
        continue;
      }
      String requestId = CheckoutRules.releaseRequestId(s.sagaId(), c.resourceType());
      boolean payment = "PAYMENT_APPROVAL".equals(c.resourceType());
      Result r =
          payment
              ? participants.compensationCancel(
                  c.resourceId(),
                  requestId,
                  s.sagaId(),
                  String.valueOf(s.lastErrorStep()),
                  CheckoutRules.cancelReason(s.lastErrorStep()))
              : participants.release(c.resourceType(), c.resourceId(), requestId, s.sagaId());
      Instant now = clock.instant();
      String state = participants.resourceState(c.resourceType(), c.resourceId());
      boolean verified =
          payment ? "CANCELLED".equals(state) : "RELEASED".equals(state) || "EXPIRED".equals(state);
      if (!verified && payment) {
        String next = r instanceof Rejected ? "RELEASE_FAILED" : "RELEASE_UNKNOWN";
        store.releaseState(s.sagaId(), c.resourceType(), next, requestId, null);
        store.touch(s.sagaId(), now);
        log.warn("사가 {} 결제 {} 보상 취소 미확인({}) — 자원 해제 보류", s.sagaId(), c.resourceId(), state);
        return;
      }
      if (verified) {
        store.releaseState(s.sagaId(), c.resourceType(), "RELEASED", requestId, now);
        compensationExecuted(s, c, "SUCCESS", now);
      } else {
        allReleased = false;
        String next = r instanceof Rejected ? "RELEASE_FAILED" : "RELEASE_UNKNOWN";
        store.releaseState(s.sagaId(), c.resourceType(), next, requestId, null);
        log.warn(
            "사가 {} {} {} 해제 미확인({}) — 재시도 대기", s.sagaId(), c.resourceType(), c.resourceId(), state);
      }
    }
    Instant now = clock.instant();
    if (allReleased) {
      store.update(
          s.sagaId(),
          "COMPENSATED",
          s.currentStep(),
          s.resourceExpiresAt(),
          s.payment(),
          s.lastErrorStep(),
          s.lastErrorCode(),
          s.lastErrorMessage(),
          now,
          true);
      stateChanged(
          s.sagaId(),
          s.orderId(),
          s.currentStep(),
          "COMPENSATED",
          s.lastErrorCode(),
          s.lastErrorMessage(),
          now);
    } else {
      store.touch(s.sagaId(), now);
    }
  }

  // ---------- 조회 ----------

  public Optional<SagaRow> find(String sagaId) {
    return store.find(sagaId);
  }

  public List<StepRow> steps(String sagaId) {
    return store.steps(sagaId);
  }

  public List<CompensationRow> compensations(String sagaId) {
    return store.compensations(sagaId);
  }

  public List<SagaRow> list(
      @Nullable String orderId, @Nullable String draftId, @Nullable String state, int size) {
    return store.list(orderId, draftId, state, size);
  }

  // ---------- 명령 본문 ----------

  private Map<String, Object> reserveBody(SagaRow s) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", s.orderId());
    b.put("mfcId", s.mfcId());
    b.put("ttlMinutes", CheckoutRules.HOLD_TTL_MINUTES);
    List<Map<String, Object>> lines = new java.util.ArrayList<>();
    for (JsonNode l : s.lines()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("orderLineId", l.path("orderLineId").asString());
      line.put("sku", l.path("sku").asString());
      line.put("qty", l.path("quantity").asInt());
      lines.add(line);
    }
    b.put("lines", lines);
    return b;
  }

  private Map<String, Object> capacityBody(SagaRow s, Instant now) {
    Window w =
        CheckoutRules.capacityWindow(s.deliveryMode(), s.arrivalDate(), s.arrivalStart(), now);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", s.orderId());
    b.put("mfcId", s.mfcId());
    b.put("window", w.iso());
    b.put("ttlMinutes", CheckoutRules.HOLD_TTL_MINUTES);
    b.put("demand", mapper.convertValue(s.demand(), Map.class));
    return b;
  }

  private Map<String, Object> slotBody(SagaRow s) {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("date", String.valueOf(s.arrivalDate()));
    window.put("start", s.arrivalStart());
    window.put("end", s.arrivalEnd());
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", s.orderId());
    b.put("draftId", s.draftId());
    b.put("sagaId", s.sagaId());
    b.put("mfcId", s.mfcId());
    b.put("window", window);
    b.put("ttlSeconds", CheckoutRules.HOLD_TTL_MINUTES * 60);
    return b;
  }

  private Map<String, Object> paymentBody(SagaRow s, Instant now) {
    Instant resourceExpires =
        s.resourceExpiresAt() != null
            ? s.resourceExpiresAt()
            : now.plus(CheckoutRules.HOLD_TTL_MINUTES, ChronoUnit.MINUTES);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", s.orderId());
    b.put("draftId", s.draftId());
    b.put("sagaId", s.sagaId());
    b.put("amount", s.amount());
    b.put("currency", "KRW");
    b.put("method", "TOSS_PAYMENTS");
    b.put("customerRef", s.customerRef());
    if (s.orderName() != null) {
      b.put("orderName", s.orderName());
    }
    b.put("requiresAdultVerification", s.containsAlcohol());
    b.put(
        "resourceExpiresAt",
        CheckoutRules.paymentDeadline(resourceExpires, s.quoteExpiresAt()).toString());
    b.put("priceValidUntil", s.quoteExpiresAt().toString());
    return b;
  }

  private static String target(String step) {
    return switch (step) {
      case "RESERVE_INVENTORY" -> Participants.INVENTORY;
      case "HOLD_CAPACITY" -> Participants.CAPACITY;
      case "HOLD_SLOT" -> Participants.SLOT;
      default -> Participants.PAYMENT;
    };
  }

  private static String next(String step) {
    int i = CheckoutRules.STEPS.indexOf(step);
    return i < 0 || i + 1 >= CheckoutRules.STEPS.size() ? step : CheckoutRules.STEPS.get(i + 1);
  }

  private static void putIfPresent(ObjectNode n, String field, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      n.put(field, value);
    }
  }

  // ---------- 사건 ----------

  private void stateChanged(
      String sagaId,
      String orderId,
      String step,
      String state,
      @Nullable String code,
      @Nullable String message,
      Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sagaId", sagaId);
    payload.put("orderId", orderId);
    payload.put("step", step);
    payload.put("state", state);
    if (code != null) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("code", code);
      if (message != null) {
        err.put("message", message);
      }
      payload.put("lastError", err);
    }
    payload.put("transitionedAt", at.toString());
    publish("CheckoutSagaStateChanged", sagaId, payload);
  }

  private void compensationExecuted(SagaRow s, CompensationRow c, String result, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sagaId", s.sagaId());
    payload.put("orderId", s.orderId());
    payload.put("resourceType", c.resourceType());
    payload.put("resourceId", c.resourceId());
    payload.put("result", result);
    payload.put("executedAt", at.toString());
    publish("CompensationExecuted", s.sagaId(), payload);
  }

  private void publish(String type, String sagaId, Map<String, Object> payload) {
    int version = store.find(sagaId).map(SagaRow::version).orElse(0);
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "CheckoutSaga",
            sagaId,
            version,
            null,
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, TOPIC);
  }
}
