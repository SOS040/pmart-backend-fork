package com.pmart.order.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.PmartException;
import com.pmart.order.infrastructure.OrchestrationClient;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.TimeChangeStore;
import com.pmart.order.infrastructure.TimeChangeStore.Row;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 예약배달 시간 변경(S4, 2026-11-16 — CP-1 2부, OMS §4·Commerce §9.4). 고객 요청을 검증해 PENDING 으로 남기고
 * orchestration S4 사가를 시작한다. 사가 결과(CONFIRMED)가 오면 주문 도착 시간대·버전을 바꾸고 {@code ReservationTimeChanged},
 * REJECTED 면 기존 약속 그대로.
 */
@Service
public class TimeChangeService {

  private static final Logger log = LoggerFactory.getLogger(TimeChangeService.class);
  static final String TOPIC = "order.order.v1";

  private final OrderStore orders;
  private final TimeChangeStore changes;
  private final OrchestrationClient orchestration;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public TimeChangeService(
      OrderStore orders,
      TimeChangeStore changes,
      OrchestrationClient orchestration,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.orders = orders;
    this.changes = changes;
    this.orchestration = orchestration;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  /** 고객 요청. 반환 = requestId(202 — 접수). */
  public String request(
      String accountId,
      String orderId,
      String idempotencyKey,
      String source,
      @Nullable Integer expectedVersion,
      LocalDate date,
      String start,
      String end) {
    return idempotency
        .execute(
            new RequestKey(source, idempotencyKey, "requestReservationTimeChange", accountId),
            Map.of(
                "orderId", orderId,
                "date", date.toString(),
                "start", start,
                "end", end),
            String.class,
            () -> {
              OrderRow o =
                  orders
                      .lock(orderId)
                      .filter(x -> x.accountId().equals(accountId))
                      .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
              if (!"SCHEDULED".equals(o.deliveryMode()) || o.arrivalDate() == null) {
                throw new PmartException(
                    "NOT_SCHEDULED",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "예약배달 주문만 시간을 바꿀 수 있습니다.",
                    false,
                    List.of());
              }
              if (!"CONFIRMED".equals(o.status())) {
                throw PmartException.invalidState("상품 준비가 시작되어 시간을 바꿀 수 없습니다(" + o.status() + ").");
              }
              if (expectedVersion != null && expectedVersion != o.version()) {
                throw PmartException.versionConflict(
                    "주문이 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + o.version() + ").");
              }
              if (date.equals(o.arrivalDate()) && start.equals(o.arrivalStart())) {
                throw new PmartException(
                    "SAME_SLOT",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "지금과 같은 시간대입니다.",
                    false,
                    List.of());
              }
              if (changes.pending(orderId)) {
                throw new PmartException(
                    "TIME_CHANGE_IN_PROGRESS",
                    HttpStatus.CONFLICT,
                    "진행 중인 시간 변경이 있습니다.",
                    false,
                    List.of());
              }
              Instant now = clock.instant();
              String requestId = "RTC-" + UUID.randomUUID();
              changes.insert(
                  new Row(
                      requestId,
                      orderId,
                      accountId,
                      o.arrivalDate(),
                      o.arrivalStart(),
                      o.arrivalEnd(),
                      date,
                      start,
                      end,
                      "PENDING",
                      null,
                      null,
                      now,
                      null));
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("orderId", orderId);
              body.put("requestId", requestId);
              body.put("mfcId", o.mfcId());
              body.put(
                  "currentArrivalWindow",
                  Map.of(
                      "date", o.arrivalDate().toString(),
                      "start", o.arrivalStart(),
                      "end", o.arrivalEnd()));
              body.put(
                  "newArrivalWindow", Map.of("date", date.toString(), "start", start, "end", end));
              changes.sagaStarted(requestId, orchestration.startTimeChange(requestId, body));
              log.info(
                  "주문 {} 시간 변경 요청 {} — {} {} → {} {}",
                  orderId,
                  requestId,
                  o.arrivalDate(),
                  o.arrivalStart(),
                  date,
                  start);
              return requestId;
            })
        .value();
  }

  public Row get(String accountId, String orderId, String requestId) {
    return changes
        .find(orderId, requestId)
        .filter(r -> r.accountId().equals(accountId))
        .orElseThrow(() -> PmartException.notFound("TIME_CHANGE_NOT_FOUND", "시간 변경 요청이 없습니다."));
  }

  /** 사가 결과(내부). 주문 행을 먼저 잠근다 — 고객 요청 트랜잭션이 커밋된 뒤 반영. 이미 결정됐으면 그대로. */
  public CommandResponse result(
      String idempotencyKey,
      String source,
      String orderId,
      String requestId,
      String result,
      @Nullable String reason,
      @Nullable String sagaId) {
    return idempotency
        .execute(
            new RequestKey(source, idempotencyKey, "recordReservationTimeChangeResult", "system"),
            Map.of(
                "orderId", orderId,
                "requestId", requestId,
                "result", result,
                "reason", String.valueOf(reason)),
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              OrderRow o =
                  orders
                      .lock(orderId)
                      .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
              Row r =
                  changes
                      .find(orderId, requestId)
                      .orElseThrow(
                          () ->
                              PmartException.notFound("TIME_CHANGE_NOT_FOUND", "시간 변경 요청이 없습니다."));
              if (!"PENDING".equals(r.state())) {
                return applied(idempotencyKey, requestId, o.version(), now);
              }
              if (!"CONFIRMED".equals(result)) {
                changes.decide(requestId, "REJECTED", reason, sagaId, now);
                log.info("주문 {} 시간 변경 {} 거절 — {}", orderId, requestId, reason);
                return applied(idempotencyKey, requestId, o.version(), now);
              }
              int version = o.version() + 1;
              orders.updateArrival(orderId, r.toDate(), r.toStart(), r.toEnd(), version);
              changes.decide(requestId, "CONFIRMED", null, sagaId, now);
              Map<String, Object> p = new LinkedHashMap<>();
              p.put("orderId", orderId);
              p.put("orderVersion", version);
              p.put("requestId", requestId);
              p.put(
                  "previousArrivalWindow",
                  Map.of(
                      "date", r.fromDate().toString(), "start", r.fromStart(), "end", r.fromEnd()));
              p.put(
                  "arrivalWindow",
                  Map.of("date", r.toDate().toString(), "start", r.toStart(), "end", r.toEnd()));
              p.put("changedAt", now.toString());
              events.publish(
                  codec.create(
                      events.sourceSystem(),
                      "ReservationTimeChanged",
                      1,
                      "Order",
                      orderId,
                      version,
                      new EventEnvelope.Actor(
                          EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
                      EventEnvelope.EventSource.REAL,
                      p),
                  TOPIC);
              log.info("주문 {} 시간 변경 {} 확정 → {} {}", orderId, requestId, r.toDate(), r.toStart());
              return applied(idempotencyKey, requestId, version, now);
            })
        .value();
  }

  private static CommandResponse applied(String key, String id, int version, Instant now) {
    return new CommandResponse(CommandStatus.APPLIED, key, id, null, version, false, null, now);
  }
}
