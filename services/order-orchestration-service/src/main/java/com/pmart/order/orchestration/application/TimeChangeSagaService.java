package com.pmart.order.orchestration.application;

import com.pmart.order.orchestration.domain.CheckoutRules;
import com.pmart.order.orchestration.domain.TimeChangeRules;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.SagaStore;
import com.pmart.order.orchestration.infrastructure.SagaStore.CompensationRow;
import com.pmart.order.orchestration.infrastructure.TimeChangeStore;
import com.pmart.order.orchestration.infrastructure.TimeChangeStore.Row;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * S4 예약 시간 변경 사가(2026-11-16 — OMS §4·A.2.5, Commerce §9.4). 새 슬롯·작업 여력을 임시로 잡고(기존은 그대로), 날짜가 바뀌면
 * LOT 약속일을 재검사한 뒤, 작업 지시 시간 변경으로 준비 시작과의 경합을 판정한다(확정 지점). 성공하면 새 점유를 확정(기존 해제)하고 order 에 CONFIRMED,
 * 그 전 거절이면 새 것만 해제·약속일 되돌림 → REJECTED(기존 약속 유지). 확정 지점 뒤 거절은 상신.
 */
@Service
public class TimeChangeSagaService {

  private static final Logger log = LoggerFactory.getLogger(TimeChangeSagaService.class);

  public record Start(
      String orderId,
      String requestId,
      String mfcId,
      LocalDate oldDate,
      String oldStart,
      String oldEnd,
      LocalDate newDate,
      String newStart,
      String newEnd) {}

  private final TimeChangeStore store;
  private final SagaStore checkouts;
  private final Participants participants;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;

  public TimeChangeSagaService(
      TimeChangeStore store,
      SagaStore checkouts,
      Participants participants,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.checkouts = checkouts;
    this.participants = participants;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 시작(같은 requestId 면 그 사가). 주문에 진행 중 변경이 있으면 409. */
  public String start(Start in) {
    Optional<Row> existing = store.byRequest(in.requestId());
    if (existing.isPresent()) {
      return existing.get().sagaId();
    }
    String sagaId = "SAGA-S4-" + UUID.randomUUID();
    try {
      store.insert(
          sagaId,
          in.requestId(),
          in.orderId(),
          in.mfcId(),
          in.oldDate(),
          in.oldStart(),
          in.oldEnd(),
          in.newDate(),
          in.newStart(),
          in.newEnd(),
          clock.instant());
    } catch (DuplicateKeyException e) {
      throw new com.pmart.common.web.PmartException(
          "TIME_CHANGE_IN_PROGRESS",
          org.springframework.http.HttpStatus.CONFLICT,
          "이 주문에 진행 중인 시간 변경이 있습니다.",
          false,
          List.of());
    }
    String id = store.byRequest(in.requestId()).map(Row::sagaId).orElse(sagaId);
    log.info(
        "S4 시간 변경 사가 {} 시작 — 주문 {} {} {} → {} {}",
        id,
        in.orderId(),
        in.oldDate(),
        in.oldStart(),
        in.newDate(),
        in.newStart());
    kick(id);
    return id;
  }

  public Optional<Row> byRequest(String requestId) {
    return store.byRequest(requestId);
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException ex) {
            log.warn("S4 사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, ex.toString());
          }
        });
  }

  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    for (String id : store.due(clock.instant().minus(props.stuckAfter()))) {
      try {
        advance(id);
      } catch (RuntimeException ex) {
        log.warn("S4 사가 {} 재개 실패: {}", id, ex.toString());
      }
    }
  }

  public void advance(String sagaId) {
    tx.executeWithoutResult(
        s -> {
          Row r = store.lock(sagaId).orElse(null);
          while (r != null && "RUNNING".equals(r.state())) {
            if (!step(r)) {
              return;
            }
            r = store.find(sagaId).orElse(null);
          }
        });
  }

  private boolean step(Row r) {
    Instant now = clock.instant();
    String key = TimeChangeRules.requestId(r.sagaId(), r.currentStep());
    return switch (r.currentStep()) {
      case TimeChangeRules.FIND_RESOURCES -> find(r, now);
      case TimeChangeRules.HOLD_SLOT -> holdSlot(r, key, now);
      case TimeChangeRules.HOLD_CAPACITY -> holdCapacity(r, key, now);
      case TimeChangeRules.MOVE_LOT_DATE -> moveLot(r, key, now);
      case TimeChangeRules.RESCHEDULE_WORK -> reschedule(r, key, now);
      case TimeChangeRules.CONFIRM_SLOT ->
          after(
              r,
              participants.confirm(
                  "SLOT_HOLD", r.newSlotHoldId(), key, r.sagaId(), r.orderId(), ""),
              TimeChangeRules.CONFIRM_CAPACITY,
              now);
      case TimeChangeRules.CONFIRM_CAPACITY ->
          after(
              r,
              participants.confirm(
                  "CAPACITY_HOLD", r.newCapacityHoldId(), key, r.sagaId(), r.orderId(), ""),
              TimeChangeRules.REPORT_ORDER,
              now);
      case TimeChangeRules.RELEASE_NEW -> releaseNew(r, now);
      case TimeChangeRules.REPORT_ORDER -> report(r, key, now);
      default -> throw new IllegalStateException("알 수 없는 단계 " + r.currentStep());
    };
  }

  /** 지금 점유: 마지막으로 확정된 시간 변경의 새 점유, 없으면 주문을 확정한 S1 의 점유. 작업 지시 ID. */
  private boolean find(Row r, Instant now) {
    String checkout = checkouts.completedSagaIdByOrder(r.orderId()).orElse(null);
    if (checkout == null) {
      return reject(r, "ORDER_RESOURCES_NOT_FOUND", now);
    }
    String slot = null;
    String capacity = null;
    String reservation = null;
    for (CompensationRow c : checkouts.compensations(checkout)) {
      switch (c.resourceType()) {
        case "SLOT_HOLD" -> slot = slot == null ? c.resourceId() : slot;
        case "CAPACITY_HOLD" -> capacity = capacity == null ? c.resourceId() : capacity;
        case "INVENTORY_RESERVATION" ->
            reservation = reservation == null ? c.resourceId() : reservation;
        default -> {}
      }
    }
    Optional<Row> last = store.lastConfirmed(r.orderId());
    if (last.isPresent()) {
      slot = last.get().newSlotHoldId();
      capacity = last.get().newCapacityHoldId();
    }
    if (slot == null || capacity == null) {
      return reject(r, "NOT_SCHEDULED", now);
    }
    String fulfillmentId = participants.fulfillmentIdOf(r.orderId());
    if (fulfillmentId == null) {
      return reject(r, "WORK_ORDER_NOT_FOUND", now);
    }
    store.resources(r.sagaId(), checkout, reservation, fulfillmentId, slot, capacity, now);
    return true;
  }

  private boolean holdSlot(Row r, String key, Instant now) {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("date", r.newDate().toString());
    window.put("start", r.newStart());
    window.put("end", r.newEnd());
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", r.orderId());
    b.put("sagaId", r.sagaId());
    b.put("mfcId", r.mfcId());
    b.put("window", window);
    b.put("ttlSeconds", 1800);
    b.put("newSlotFor", r.oldSlotHoldId());
    Result res = participants.holdSlot(key, b);
    if (res instanceof Ok ok) {
      store.newSlot(r.sagaId(), ok.resultId(), TimeChangeRules.HOLD_CAPACITY, now);
      return true;
    }
    return failed(r, res, now);
  }

  private boolean holdCapacity(Row r, String key, Instant now) {
    var s = checkouts.find(r.checkoutSagaId()).orElseThrow();
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", r.orderId());
    b.put("mfcId", r.mfcId());
    b.put(
        "window", CheckoutRules.capacityWindow("SCHEDULED", r.newDate(), r.newStart(), now).iso());
    b.put("ttlMinutes", 30);
    b.put("demand", mapper.convertValue(s.demand(), Map.class));
    b.put("replacesHoldId", r.oldCapacityHoldId());
    Result res = participants.holdCapacity(key, b);
    if (res instanceof Ok ok) {
      store.newCapacity(
          r.sagaId(),
          ok.resultId(),
          r.dateChanged() && r.reservationId() != null
              ? TimeChangeRules.MOVE_LOT_DATE
              : TimeChangeRules.RESCHEDULE_WORK,
          now);
      return true;
    }
    return failed(r, res, now);
  }

  private boolean moveLot(Row r, String key, Instant now) {
    Result res =
        participants.changePromisedDate(
            r.reservationId(), key, r.orderId(), r.newDate().toString());
    if (res instanceof Ok) {
      store.lotMoved(r.sagaId(), true, TimeChangeRules.RESCHEDULE_WORK, now);
      return true;
    }
    return failed(r, res, now);
  }

  /** 확정 지점 — 작업 지시가 준비 시작 전이면 바뀐다. */
  private boolean reschedule(Row r, String key, Instant now) {
    java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
    Map<String, Object> window = new LinkedHashMap<>();
    window.put(
        "start",
        r.newDate()
            .atTime(java.time.LocalTime.parse(r.newStart()))
            .atZone(seoul)
            .toInstant()
            .toString());
    window.put(
        "end",
        r.newEnd().equals("24:00")
            ? r.newDate().plusDays(1).atStartOfDay(seoul).toInstant().toString()
            : r.newDate()
                .atTime(java.time.LocalTime.parse(r.newEnd()))
                .atZone(seoul)
                .toInstant()
                .toString());
    Result res =
        participants.rescheduleWork(r.fulfillmentId(), key, r.orderId(), r.sagaId(), window);
    if (res instanceof Ok) {
      store.step(r.sagaId(), TimeChangeRules.CONFIRM_SLOT, now);
      return true;
    }
    return failed(r, res, now);
  }

  /** 확정 지점 뒤 단계 — 업무 거절은 상신(새 점유가 만료됐을 수 있다), 모름은 sweep. */
  private boolean after(Row r, Result res, String next, Instant now) {
    if (res instanceof Ok) {
      if (TimeChangeRules.REPORT_ORDER.equals(next)) {
        store.decide(r.sagaId(), "CONFIRMED", null, next, now);
      } else {
        store.step(r.sagaId(), next, now);
      }
      return true;
    }
    if (res instanceof Rejected rj) {
      store.error(
          r.sagaId(),
          r.currentStep(),
          rj.code(),
          "시간 변경 확정 뒤 " + r.currentStep() + " 거절 — " + rj.message(),
          true,
          now);
      log.warn("S4 사가 {} 상신 — {} {}", r.sagaId(), r.currentStep(), rj.code());
      return false;
    }
    store.error(r.sagaId(), r.currentStep(), "UNKNOWN_RESULT", "결과 모름(다시 시도)", false, now);
    return false;
  }

  /** 확정 지점 전 실패 — 업무 거절이면 되돌리고 REJECTED, 모름·장애는 sweep 이 같은 키로. */
  private boolean failed(Row r, Result res, Instant now) {
    if (res instanceof Rejected rj) {
      return reject(r, rj.code(), now);
    }
    store.error(r.sagaId(), r.currentStep(), "UNKNOWN_RESULT", "결과 모름(다시 시도)", false, now);
    return false;
  }

  private boolean reject(Row r, String reason, Instant now) {
    log.info("S4 사가 {} 거절 — {} ({})", r.sagaId(), reason, r.currentStep());
    store.decide(r.sagaId(), "REJECTED", reason, TimeChangeRules.RELEASE_NEW, now);
    return true;
  }

  /** 새로 잡은 것만 해제·약속일 되돌림(기존 약속 그대로). */
  private boolean releaseNew(Row r, Instant now) {
    if (r.newSlotHoldId() != null) {
      Result a =
          participants.release(
              "SLOT_HOLD",
              r.newSlotHoldId(),
              TimeChangeRules.requestId(r.sagaId(), "RELEASE_SLOT"),
              r.sagaId());
      if (!(a instanceof Ok)) {
        return retryOrEscalate(r, a, now);
      }
    }
    if (r.newCapacityHoldId() != null) {
      Result b =
          participants.release(
              "CAPACITY_HOLD",
              r.newCapacityHoldId(),
              TimeChangeRules.requestId(r.sagaId(), "RELEASE_CAPACITY"),
              r.sagaId());
      if (!(b instanceof Ok)) {
        return retryOrEscalate(r, b, now);
      }
    }
    if (r.lotMoved()) {
      Result c =
          participants.changePromisedDate(
              r.reservationId(),
              TimeChangeRules.requestId(r.sagaId(), "REVERT_LOT_DATE"),
              r.orderId(),
              r.oldDate().toString());
      if (!(c instanceof Ok)) {
        return retryOrEscalate(r, c, now);
      }
      store.lotMoved(r.sagaId(), false, TimeChangeRules.REPORT_ORDER, now);
      return true;
    }
    store.step(r.sagaId(), TimeChangeRules.REPORT_ORDER, now);
    return true;
  }

  private boolean retryOrEscalate(Row r, Result res, Instant now) {
    boolean escalate = res instanceof Rejected;
    store.error(
        r.sagaId(),
        r.currentStep(),
        res instanceof Rejected rj ? rj.code() : "UNKNOWN_RESULT",
        "시간 변경 되돌리기 실패",
        escalate,
        now);
    return false;
  }

  private boolean report(Row r, String key, Instant now) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("result", r.outcome());
    if (r.reason() != null) {
      b.put("reason", r.reason());
    }
    b.put("sagaId", r.sagaId());
    Result res = participants.reportTimeChange(r.orderId(), r.requestId(), key, b);
    if (res instanceof Ok) {
      store.finish(r.sagaId(), now);
      log.info("S4 사가 {} 끝 — {} {}", r.sagaId(), r.outcome(), r.reason() == null ? "" : r.reason());
      return false;
    }
    if (res instanceof Rejected rj) {
      store.error(
          r.sagaId(), r.currentStep(), rj.code(), "order 결과 알림 거절 — " + rj.message(), true, now);
      return false;
    }
    store.error(r.sagaId(), r.currentStep(), "UNKNOWN_RESULT", "결과 모름(다시 시도)", false, now);
    return false;
  }
}
