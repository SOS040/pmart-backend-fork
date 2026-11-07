package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.WorkRules;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore.StopRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 단위 작업 중지(S3-a, docs/business/flows/cancel-vs-handover.md §2 a). 판정 주체는 이 서비스다 — 취소 접수 시각은 우선순위
 * 근거가 아니다(ADR-0006).
 *
 * <ul>
 *   <li>요청 수량 전부를 STOPPED(notStartedLines)로 확정하고 기록을 남긴다 — 6a 는 피킹 시작이 없다. 이 기록은 이후 같은 주문의 작업
 *       요청(ConfirmOrder)이 중지된 수량을 빼고 받는 근거다. 이미 접수된 작업 지시가 있으면 그 수량만큼 작업을 취소한다(2026-11-07).
 *   <li>같은 stopRequestId 는 기존 판정(다른 주문·수량이면 409 IDEMPOTENCY_CONFLICT). 판정과 WorkStopResult(Outbox,
 *       토픽 mfc.fulfillment.v1)는 한 트랜잭션.
 * </ul>
 */
@Service
public class WorkStopService {

  public static final String TOPIC = "mfc.fulfillment.v1";

  private static final Logger log = LoggerFactory.getLogger(WorkStopService.class);
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};

  public record Line(String orderLineId, int qty) {}

  public record StopCommand(
      String stopRequestId,
      String orderId,
      @Nullable String claimId,
      String scope,
      List<Line> lines) {}

  /** 판정(계약 StopRequestResult). 작업 지시가 생기기 전이라 포장별 판정은 없다. */
  public record Verdict(
      String stopRequestId,
      String orderId,
      String state,
      List<Line> notStartedLines,
      Instant resolvedAt) {}

  private final WorkStopStore store;
  private final WorkOrderStore works;
  private final com.pmart.mfc.fulfillment.infrastructure.TaskStore tasks;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;

  public WorkStopService(
      WorkStopStore store,
      WorkOrderStore works,
      com.pmart.mfc.fulfillment.infrastructure.TaskStore tasks,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.works = works;
    this.tasks = tasks;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Verdict stop(String idempotencyKey, String sourceSystem, StopCommand in) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, idempotencyKey, "stopOrderWork", actor()),
            in,
            Verdict.class,
            () -> {
              works.lockOrder(in.orderId()); // 같은 주문의 작업 접수와 한 줄로
              StopRow existing = store.find(in.stopRequestId()).orElse(null);
              if (existing != null) {
                if (!existing.orderId().equals(in.orderId())
                    || !lines(existing.requestedLines()).equals(in.lines())) {
                  throw PmartException.idempotencyConflict(
                      "같은 중지 요청 ID 로 다른 주문·수량이 왔습니다: " + in.stopRequestId());
                }
                return verdict(existing);
              }
              Instant now = clock.instant();
              String json = mapper.writeValueAsString(in.lines());
              if (tasks.pickStarted(in.orderId())) {
                // 피킹이 시작된 주문(6b): 집품분의 판매 제외·복귀(IV-08)와 포장별 판정이 아직 없다 — 작업을 지우지 않고 PENDING 으로
                // 둔다(S3 는 대기·재조회). 고객 자율 취소는 준비 시작(PREPARING) 뒤 받지 않으므로 드문 경합이다.
                StopRow pending =
                    new StopRow(
                        in.stopRequestId(),
                        in.orderId(),
                        in.claimId(),
                        in.scope(),
                        json,
                        "PENDING",
                        "[]",
                        now);
                store.insert(pending);
                log.warn(
                    "주문 {} 작업 중지 {} — 피킹 시작 뒤라 PENDING(IV-08 전)", in.orderId(), in.stopRequestId());
                return verdict(pending);
              }
              StopRow row =
                  new StopRow(
                      in.stopRequestId(),
                      in.orderId(),
                      in.claimId(),
                      in.scope(),
                      json,
                      "RESOLVED",
                      json, // 6a: 피킹 시작이 없다 — 요청 수량 전부 작업 전 중지
                      now);
              store.insert(row);
              boolean cancelled = cancelAcceptedWork(in, now);
              publish(row, in.lines());
              log.info(
                  "주문 {} 작업 중지 {} — 전량 STOPPED{}",
                  in.orderId(),
                  in.stopRequestId(),
                  cancelled ? " (접수된 작업 취소)" : " (작업 지시 없음)");
              return verdict(row);
            })
        .value();
  }

  /**
   * 접수된 작업 지시가 있으면 중지 수량만큼 작업 행을 취소하고, 잔량이 없는 작업·출고는 CANCELLED(슬라이스 6a — 피킹 시작 전이라 포장 판정 없음). 거절된
   * 작업 지시는 작업이 없다.
   */
  private boolean cancelAcceptedWork(StopCommand in, Instant now) {
    WorkOrderStore.WorkOrderRow w = works.findByOrder(in.orderId()).orElse(null);
    if (w == null || !"ACCEPTED".equals(w.acceptance())) {
      return false;
    }
    List<WorkOrderStore.TaskLineRow> lines = works.taskLines(w.fulfillmentId());
    Map<String, Integer> remainingByLine = new LinkedHashMap<>();
    for (WorkOrderStore.TaskLineRow l : lines) {
      remainingByLine.merge(l.orderLineId(), l.remaining(), Math::max); // PICK·PACK 은 같은 행
    }
    Map<String, Integer> cancel =
        WorkRules.cancelAgainst(
            remainingByLine,
            in.lines().stream()
                .map(l -> new WorkRules.Line(l.orderLineId(), "", l.qty()))
                .toList());
    for (WorkOrderStore.TaskLineRow l : lines) {
      int c = Math.min(l.remaining(), cancel.getOrDefault(l.orderLineId(), 0));
      if (c > 0) {
        works.cancelQty(l.taskId(), l.orderLineId(), c);
      }
    }
    works.closeEmptyTasks(w.fulfillmentId(), now);
    works.touch(w.fulfillmentId(), now);
    return true;
  }

  public Verdict get(String stopRequestId) {
    return store
        .find(stopRequestId)
        .map(this::verdict)
        .orElseThrow(() -> PmartException.notFound("STOP_REQUEST_NOT_FOUND", "중지 요청이 없습니다."));
  }

  private Verdict verdict(StopRow r) {
    return new Verdict(
        r.stopRequestId(), r.orderId(), r.state(), lines(r.notStartedLines()), r.decidedAt());
  }

  private List<Line> lines(String json) {
    return mapper.readValue(json, LINES);
  }

  private void publish(StopRow r, List<Line> notStarted) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", r.orderId());
    p.put("stopRequestId", r.stopRequestId());
    if (r.claimId() != null) {
      p.put("claimId", r.claimId());
    }
    p.put("perPackage", List.of());
    p.put("notStartedLines", notStarted);
    p.put("decidedAt", r.decidedAt().toString());
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            "WorkStopResult",
            1,
            "Fulfillment",
            r.orderId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p);
    events.publish(e, TOPIC);
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }
}
