package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules.Return;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules.Unit;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.WorkRules;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.ExecutionRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.PackageRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.ShipmentRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskLineRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore.StopRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 단위 작업 중지(S3-a, docs/business/flows/cancel-vs-handover.md §2 a). 판정 주체는 이 서비스다 — 취소 접수 시각은 우선순위
 * 근거가 아니다(ADR-0006).
 *
 * <ul>
 *   <li>피킹 전: 요청 수량 전부를 STOPPED(notStartedLines)로 확정하고 기록을 남긴다. 이 기록은 이후 같은 주문의 작업 요청
 *       (ConfirmOrder)이 중지된 수량을 빼고 받는 근거다. 이미 접수된 작업 지시가 있으면 그 수량만큼 작업을 취소한다(2026-11-07).
 *   <li>피킹 뒤(IV-08, ADR-0046, 전체 중지만): PENDING 으로 기록 → 남은 작업 행 취소·작업 종료·밀봉 포장 INVALIDATED·출고
 *       CANCELLED ·복귀 작업(RETURN, WAITING) → 커밋 뒤 inventory cancel-returns(키 = 중지 요청 ID). 반영되면
 *       RESOLVED(포장·출고별 STOPPED)· RETURN READY·WorkStopResult. 결과 모름·미전달·진행 중 실적 대기는 sweep 이 다시, 재고
 *       거절은 last_error 로 남긴다(운영자).
 *   <li>피킹 뒤 부분 중지는 아직 없다 — PENDING 으로 둔다(고객 부분 취소는 접수하지 않는다).
 *   <li>같은 stopRequestId 는 기존 판정(다른 주문·수량이면 409 IDEMPOTENCY_CONFLICT). 판정과 WorkStopResult(Outbox,
 *       토픽 mfc.fulfillment.v1)는 한 트랜잭션.
 * </ul>
 */
@Service
public class WorkStopService {

  public static final String TOPIC = "mfc.fulfillment.v1";
  public static final String RETURN_REASON = "CANCEL_AFTER_PICK";
  static final String NOT_PICKED = "NOT_PICKED";

  private static final Logger log = LoggerFactory.getLogger(WorkStopService.class);
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};
  private static final TypeReference<List<PackageVerdict>> VERDICTS = new TypeReference<>() {};
  private static final TypeReference<List<TaskService.ExecLine>> EXEC_LINES =
      new TypeReference<>() {};
  private static final TypeReference<List<Content>> CONTENTS = new TypeReference<>() {};

  public record Line(String orderLineId, int qty) {}

  public record StopCommand(
      String stopRequestId,
      String orderId,
      @Nullable String claimId,
      String scope,
      List<Line> lines) {}

  /** 포장(또는 출고의 포장 전 단위 — packageId null)별 판정 한 줄. */
  public record StoppedLine(String orderLineId, int qty, String workStateAtStop) {}

  public record PackageVerdict(
      @Nullable String packageId,
      String shipmentId,
      String verdict,
      List<StoppedLine> stoppedLines) {}

  /** 판정(계약 StopRequestResult). */
  public record Verdict(
      String stopRequestId,
      String orderId,
      String state,
      List<Line> notStartedLines,
      List<PackageVerdict> packages,
      Instant resolvedAt) {}

  private final WorkStopStore store;
  private final WorkOrderStore works;
  private final TaskStore tasks;
  private final InventoryClient inventory;
  private final FulfillmentEvents fulfillmentEvents;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public WorkStopService(
      WorkStopStore store,
      WorkOrderStore works,
      TaskStore tasks,
      InventoryClient inventory,
      FulfillmentEvents fulfillmentEvents,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.store = store;
    this.works = works;
    this.tasks = tasks;
    this.inventory = inventory;
    this.fulfillmentEvents = fulfillmentEvents;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  public Verdict stop(String idempotencyKey, String sourceSystem, StopCommand in) {
    Verdict v =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "stopOrderWork", actor()),
                in,
                Verdict.class,
                () -> decide(in))
            .value();
    if (!"PENDING".equals(v.state())) {
      return v;
    }
    finish(v.stopRequestId()); // 커밋 뒤 재고 반영 — 안 되면 sweep 이 다시
    return get(v.stopRequestId());
  }

  private Verdict decide(StopCommand in) {
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
      StopRow pending =
          new StopRow(
              in.stopRequestId(),
              in.orderId(),
              in.claimId(),
              in.scope(),
              json,
              "PENDING",
              "[]",
              now,
              null,
              "[]",
              null,
              null);
      store.insert(pending);
      if ("FULL".equals(in.scope())) {
        boolean ready = prepare(pending, now);
        log.info(
            "주문 {} 작업 중지 {} — 피킹 시작 뒤, {}",
            in.orderId(),
            in.stopRequestId(),
            ready ? "작업 종료·복귀 작업 생성, 재고 반영 대기" : "진행 중 실적이 있어 준비 대기");
      } else {
        log.warn("주문 {} 작업 중지 {} — 피킹 시작 뒤 부분 중지는 아직 없어 PENDING", in.orderId(), in.stopRequestId());
      }
      return verdict(store.find(in.stopRequestId()).orElseThrow());
    }
    StopRow row =
        new StopRow(
            in.stopRequestId(),
            in.orderId(),
            in.claimId(),
            in.scope(),
            json,
            "RESOLVED",
            json, // 피킹 전 — 요청 수량 전부 작업 전 중지
            now,
            null,
            "[]",
            null,
            now);
    store.insert(row);
    boolean cancelled = cancelAcceptedWork(in, now);
    publish(row, in.lines(), List.of());
    log.info(
        "주문 {} 작업 중지 {} — 전량 STOPPED{}",
        in.orderId(),
        in.stopRequestId(),
        cancelled ? " (접수된 작업 취소)" : " (작업 지시 없음)");
    return verdict(row);
  }

  /**
   * 접수된 작업 지시가 있으면 중지 수량만큼 작업 행을 취소하고, 잔량이 없는 작업·출고는 CANCELLED(피킹 시작 전이라 포장 판정 없음). 거절된 작업 지시는 작업이
   * 없다.
   */
  private boolean cancelAcceptedWork(StopCommand in, Instant now) {
    WorkOrderStore.WorkOrderRow w = works.findByOrder(in.orderId()).orElse(null);
    if (w == null || !"ACCEPTED".equals(w.acceptance())) {
      return false;
    }
    List<TaskLineRow> lines = works.taskLines(w.fulfillmentId());
    Map<String, Integer> remainingByLine = new LinkedHashMap<>();
    for (TaskLineRow l : lines) {
      remainingByLine.merge(l.orderLineId(), l.remaining(), Math::max); // PICK·PACK 은 같은 행
    }
    Map<String, Integer> cancel =
        WorkRules.cancelAgainst(
            remainingByLine,
            in.lines().stream()
                .map(l -> new WorkRules.Line(l.orderLineId(), "", l.qty()))
                .toList());
    for (TaskLineRow l : lines) {
      int c = Math.min(l.remaining(), cancel.getOrDefault(l.orderLineId(), 0));
      if (c > 0) {
        works.cancelQty(l.taskId(), l.orderLineId(), c);
      }
    }
    works.closeEmptyTasks(w.fulfillmentId(), now);
    works.touch(w.fulfillmentId(), now);
    return true;
  }

  // ---------- 집품 뒤 중지(IV-08) ----------

  /**
   * 작업을 끝내고 복귀 대상을 계산해 중지 기록에 넣는다(주문 잠금 트랜잭션 안). 진행 중(재고 결과 대기) 실적·포장이 있으면 false — 그 결과가 나야 집품·포장량이
   * 확정된다.
   */
  private boolean prepare(StopRow stop, Instant now) {
    WorkOrderStore.WorkOrderRow w = works.findByOrder(stop.orderId()).orElseThrow();
    String fid = w.fulfillmentId();
    tasks.lockTasksOf(fid); // 실적 접수·포장 밀봉과 한 줄로
    if (tasks.inFlight(fid) > 0) {
      return false;
    }
    List<TaskRow> taskRows = works.tasks(fid);
    List<TaskLineRow> taskLines = works.taskLines(fid);
    List<Return> all = new ArrayList<>();
    List<PackageVerdict> verdicts = new ArrayList<>();
    for (ShipmentRow s : works.shipments(fid)) {
      if ("CANCELLED".equals(s.state())) {
        continue;
      }
      List<Unit> picked = new ArrayList<>();
      for (ExecutionRow e : tasks.executions(s.shipmentId(), "PICK", "APPLIED")) {
        for (TaskService.ExecLine l : mapper.readValue(e.lines(), EXEC_LINES)) {
          if (l.pickedQty() > 0 && l.lotId() != null) {
            picked.add(
                new Unit(
                    l.orderLineId(),
                    l.sku(),
                    l.lotId(),
                    Objects.requireNonNull(e.toLocation()),
                    l.pickedQty()));
          }
        }
      }
      List<Unit> packed = new ArrayList<>();
      for (PackageRow p : tasks.packages(s.shipmentId())) {
        if (!"SEALED".equals(p.state())) {
          continue;
        }
        Map<String, Integer> byLine = new LinkedHashMap<>();
        for (Content c : mapper.readValue(p.contents(), CONTENTS)) {
          packed.add(new Unit(c.orderLineId(), c.sku(), c.lotId(), p.packingLocation(), c.qty()));
          byLine.merge(c.orderLineId(), c.qty(), Integer::sum);
        }
        verdicts.add(
            new PackageVerdict(
                p.packageId(),
                s.shipmentId(),
                "STOPPED",
                byLine.entrySet().stream()
                    .map(x -> new StoppedLine(x.getKey(), x.getValue(), CancelReturnRules.PACKED))
                    .toList()));
      }
      List<Return> rs = CancelReturnRules.returns(picked, packed);

      // 포장 전 단위: 집지 않은 양(PICK 잔량) + 집었지만 포장 안 한 양
      Map<String, Integer> notPicked = new LinkedHashMap<>();
      Set<String> pickTasks = new java.util.HashSet<>();
      for (TaskRow t : taskRows) {
        if (t.shipmentId().equals(s.shipmentId()) && "PICK".equals(t.taskType())) {
          pickTasks.add(t.taskId());
        }
      }
      for (TaskLineRow l : taskLines) {
        if (pickTasks.contains(l.taskId()) && l.remaining() > 0) {
          notPicked.merge(l.orderLineId(), l.remaining(), Integer::sum);
        }
      }
      List<StoppedLine> loose = new ArrayList<>();
      notPicked.forEach((line, q) -> loose.add(new StoppedLine(line, q, NOT_PICKED)));
      Map<String, Integer> pickedLoose = new LinkedHashMap<>();
      for (Return r : rs) {
        if (CancelReturnRules.PICKED.equals(r.workState())) {
          pickedLoose.merge(r.orderLineId(), r.qty(), Integer::sum);
        }
      }
      pickedLoose.forEach(
          (line, q) -> loose.add(new StoppedLine(line, q, CancelReturnRules.PICKED)));
      if (!loose.isEmpty()) {
        verdicts.add(new PackageVerdict(null, s.shipmentId(), "STOPPED", loose));
      }

      closeShipmentWork(s.shipmentId(), taskRows, taskLines, now);
      tasks.invalidateSealed(s.shipmentId(), now);
      tasks.cancelShipment(s.shipmentId(), now);
      if (!rs.isEmpty()) {
        createReturnTask(s.shipmentId(), rs, now);
      }
      all.addAll(rs);
    }
    works.touch(fid, now);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", RETURN_REASON);
    body.put("returns", all);
    store.prepared(
        stop.stopRequestId(), mapper.writeValueAsString(body), mapper.writeValueAsString(verdicts));
    return true;
  }

  /** 출고의 PICK·PACK 남은 행 취소, 끝나지 않은 작업은 완료량이 있으면 CLOSED_PARTIAL, 없으면 CANCELLED. */
  private void closeShipmentWork(
      String shipmentId, List<TaskRow> taskRows, List<TaskLineRow> taskLines, Instant now) {
    for (TaskRow t : taskRows) {
      if (!t.shipmentId().equals(shipmentId)
          || !("PICK".equals(t.taskType()) || "PACK".equals(t.taskType()))) {
        continue;
      }
      boolean anyDone = false;
      for (TaskLineRow l : taskLines) {
        if (!l.taskId().equals(t.taskId())) {
          continue;
        }
        anyDone |= l.completedQty() > 0;
        if (l.remaining() > 0) {
          works.cancelQty(t.taskId(), l.orderLineId(), l.remaining());
        }
      }
      if (Set.of("COMPLETED", "CANCELLED", "CLOSED_PARTIAL").contains(t.state())) {
        continue;
      }
      String to = anyDone ? "CLOSED_PARTIAL" : "CANCELLED";
      TaskStore.TaskInfo info = tasks.find(t.taskId()).orElseThrow();
      tasks.setState(t.taskId(), to, "주문 취소(집품 뒤)", false, now);
      fulfillmentEvents.taskProgressed(info, to, t.state(), info.version() + 1, null, null, now);
    }
  }

  private void createReturnTask(String shipmentId, List<Return> rs, Instant now) {
    String taskId = shipmentId + "-RETURN";
    Map<String, Integer> byLine = new LinkedHashMap<>();
    for (Return r : rs) {
      byLine.merge(r.orderLineId(), r.qty(), Integer::sum);
    }
    works.insertTask(
        new TaskRow(taskId, shipmentId, "RETURN", "WAITING", null, "CANCEL_RETURN_PENDING", 1),
        byLine.entrySet().stream()
            .map(e -> new TaskLineRow(taskId, shipmentId, e.getKey(), e.getValue(), 0, 0))
            .toList(),
        now);
    tasks.setReturns(taskId, mapper.writeValueAsString(rs));
  }

  /**
   * PENDING 중지를 끝까지(트랜잭션 밖). 준비 전이면 준비하고, 재고 cancel-returns 가 반영되면 RESOLVED·RETURN 작업 READY·
   * WorkStopResult.
   */
  public void finish(String stopRequestId) {
    StopRow r = store.find(stopRequestId).orElse(null);
    if (r == null || !"PENDING".equals(r.state()) || !"FULL".equals(r.scope())) {
      return;
    }
    if (r.cancelReturn() == null) {
      boolean ready =
          Boolean.TRUE.equals(
              tx.execute(
                  s -> {
                    works.lockOrder(r.orderId());
                    StopRow again = store.find(stopRequestId).orElseThrow();
                    if (!"PENDING".equals(again.state()) || again.cancelReturn() != null) {
                      return true;
                    }
                    return prepare(again, clock.instant());
                  }));
      if (!ready) {
        return;
      }
    }
    StopRow p = store.find(stopRequestId).orElseThrow();
    if (!"PENDING".equals(p.state()) || p.cancelReturn() == null) {
      return;
    }
    WorkOrderStore.WorkOrderRow w = works.findByOrder(p.orderId()).orElseThrow();
    InventoryClient.Commit c =
        inventory.cancelReturn(w.reservationId(), stopRequestId, p.cancelReturn());
    switch (c.outcome()) {
      case "APPLIED" ->
          tx.executeWithoutResult(
              s -> {
                works.lockOrder(p.orderId());
                Instant now = clock.instant();
                if (store.resolve(stopRequestId, now) == 0) {
                  return; // 다른 시도가 먼저 끝냈다
                }
                List<String> released = tasks.releaseReturns(w.fulfillmentId(), now);
                StopRow done = store.find(stopRequestId).orElseThrow();
                publish(done, List.of(), verdicts(done));
                log.info(
                    "주문 {} 작업 중지 {} RESOLVED — 집품분 취소 복귀 대기, 복귀 작업 {}",
                    p.orderId(),
                    stopRequestId,
                    released);
              });
      case "REJECTED" -> {
        String err = c.code() + (c.detail() == null ? "" : ": " + c.detail());
        store.recordError(stopRequestId, err);
        log.error("주문 {} 작업 중지 {} — 재고가 취소 복귀를 거절: {}", p.orderId(), stopRequestId, err);
      }
      default ->
          log.warn(
              "주문 {} 작업 중지 {} — 재고 취소 복귀 {}({}), sweep 이 다시",
              p.orderId(),
              stopRequestId,
              c.outcome(),
              c.detail());
    }
  }

  /**
   * 집품 뒤 중지 PENDING 을 다시(준비 대기·재고 결과 모름·미전달). 재고 거절은 같은 본문이라 다시 보내도 거절이다 — 그대로 다시 보낸다(원인 해소 뒤 통과).
   */
  @Scheduled(
      fixedDelayString = "${pmart.fulfillment.stop-sweep-interval:PT30S}",
      initialDelayString = "PT25S")
  public void sweepPending() {
    for (String id : store.pending(50)) {
      try {
        finish(id);
      } catch (RuntimeException e) {
        log.warn("작업 중지 {} sweep 실패: {}", id, e.getMessage());
      }
    }
  }

  public Verdict get(String stopRequestId) {
    return store
        .find(stopRequestId)
        .map(this::verdict)
        .orElseThrow(() -> PmartException.notFound("STOP_REQUEST_NOT_FOUND", "중지 요청이 없습니다."));
  }

  private Verdict verdict(StopRow r) {
    return new Verdict(
        r.stopRequestId(),
        r.orderId(),
        r.state(),
        lines(r.notStartedLines()),
        verdicts(r),
        r.resolvedAt() == null ? r.decidedAt() : r.resolvedAt());
  }

  private List<PackageVerdict> verdicts(StopRow r) {
    return mapper.readValue(r.packageVerdicts(), VERDICTS);
  }

  private List<Line> lines(String json) {
    return mapper.readValue(json, LINES);
  }

  private void publish(StopRow r, List<Line> notStarted, List<PackageVerdict> packages) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", r.orderId());
    p.put("stopRequestId", r.stopRequestId());
    if (r.claimId() != null) {
      p.put("claimId", r.claimId());
    }
    List<Map<String, Object>> perPackage = new ArrayList<>();
    for (PackageVerdict v : packages) {
      if (v.packageId() == null) {
        continue; // 사건은 포장 단위만(포장 전 단위는 조회 응답에)
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("packageId", v.packageId());
      m.put("state", v.verdict());
      m.put(
          "stoppedLines",
          v.stoppedLines().stream()
              .map(l -> Map.of("orderLineId", l.orderLineId(), "qty", l.qty()))
              .toList());
      perPackage.add(m);
    }
    p.put("perPackage", perPackage);
    p.put("notStartedLines", notStarted);
    p.put("decidedAt", (r.resolvedAt() == null ? r.decidedAt() : r.resolvedAt()).toString());
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
