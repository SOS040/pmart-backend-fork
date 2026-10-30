package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.ReservationState.Transition;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import com.pmart.mfc.inventory.domain.StockRules.Shortage;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.EntryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약(S1 a): 예약 → 확정 / 해제 / 만료. 모든 명령은 {@link RequestIdempotency} 안에서 한 트랜잭션이다.
 *
 * <ul>
 *   <li>예약: 같은 SKU 를 합산하고, 취급 OFF 면 SKU_NOT_HANDLED, 잔량 행을 SKU 오름차순으로 잠근 뒤 한 SKU 라도 A 가 모자라면
 *       INSUFFICIENT_STOCK 으로 전체 거절(TC-09). 거절은 예외라 멱등 기록에 남지 않는다 — 같은 키로 다시 보내면 그때 잔량으로 다시 판정한다.
 *   <li>잠금 순서는 예약 행 → 잔량 행(SKU 오름차순). 만료 작업도 같은 순서라 교착이 없다.
 *   <li>확정은 R 총량 불변, 해제·만료는 R−q.
 * </ul>
 */
@Service
public class ReservationService {

  public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
  public static final String PARTIAL_RELEASE_NOT_SUPPORTED = "PARTIAL_RELEASE_NOT_SUPPORTED";

  /** 계약 InventoryHoldExpired.v1 의 토픽(집합 이름이 아니라 서비스 토픽). */
  public static final String TOPIC = "mfc.inventory.v1";

  static final int DEFAULT_TTL_MINUTES = 10;
  static final String OP_RESERVE = "reserveInventory";
  static final String OP_CONFIRM = "confirmReservation";
  static final String OP_RELEASE = "releaseReservation";

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  /** 예약 명령. lotConstraint 는 아직 받지 않는다(api 가 400). */
  public record ReserveCommand(
      String orderId, String mfcId, @Nullable Integer ttlMinutes, List<Line> lines) {}

  /** 멱등 비교용(경로 변수 포함). */
  record ConfirmRequest(
      String reservationId,
      String orderId,
      @Nullable String paymentAttemptId,
      @Nullable Integer expectedVersion) {}

  record ReleaseRequest(
      String reservationId,
      String reason,
      @Nullable String sourceRef,
      @Nullable List<Line> lines,
      @Nullable Integer expectedVersion) {}

  /** 조회 결과. */
  public record ReservationView(ReservationRow row, Map<String, Integer> lines) {}

  private final ReservationStore reservations;
  private final StockStore stock;
  private final SkuHandlingStore handlings;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public ReservationService(
      ReservationStore reservations,
      StockStore stock,
      SkuHandlingStore handlings,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.reservations = reservations;
    this.stock = stock;
    this.handlings = handlings;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  // ---------- 예약 ----------

  public CommandResponse reserve(String requestId, String sourceSystem, ReserveCommand cmd) {
    SortedMap<String, Integer> requested;
    try {
      requested = StockRules.sumBySku(cmd.lines());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    if (requested.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lines 가 비어 있습니다.");
    }
    int ttl = cmd.ttlMinutes() == null ? DEFAULT_TTL_MINUTES : cmd.ttlMinutes();
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_RESERVE),
            cmd,
            CommandResponse.class,
            () -> {
              requireHandled(cmd.mfcId(), requested);
              Map<String, Balance> locked = stock.lockBalances(cmd.mfcId(), requested.keySet());
              List<Shortage> shortages = StockRules.shortages(requested, locked);
              if (!shortages.isEmpty()) {
                throw insufficient(shortages);
              }
              Instant at = clock.instant();
              String reservationId = "RSV-" + UUID.randomUUID();
              int total = requested.values().stream().mapToInt(Integer::intValue).sum();
              reservations.insert(
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  at.plus(Duration.ofMinutes(ttl)),
                  sourceSystem,
                  requestId,
                  at,
                  requested);
              String commitId =
                  newCommit(
                      "RESERVE", cmd.mfcId(), requestId, sourceSystem, reservationId, total, at);
              requested.forEach(
                  (sku, qty) -> {
                    long v = stock.addReserved(cmd.mfcId(), sku, qty, at);
                    stock.insertEntry(commitId, cmd.mfcId(), new EntryRow(sku, "R", qty, v));
                  });
              log.info(
                  "reservation {} HELD for order {} at {} ({} units, ttl {}m)",
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  total,
                  ttl);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, 1, false, null, at);
            })
        .value();
  }

  private void requireHandled(String mfcId, SortedMap<String, Integer> requested) {
    Map<String, HandlingRow> rows = handlings.findAll(mfcId, requested.keySet());
    List<PmartException.Detail> off = new ArrayList<>();
    for (String sku : requested.keySet()) {
      HandlingRow r = rows.get(sku);
      if (r == null || !r.enabled()) {
        off.add(new PmartException.Detail(sku, "HANDLING_OFF"));
      }
    }
    if (!off.isEmpty()) {
      throw new PmartException(
          StockRules.SKU_NOT_HANDLED,
          HttpStatus.UNPROCESSABLE_CONTENT,
          off.size() + "개 SKU 가 이 센터에서 취급 OFF 입니다.",
          false,
          off);
    }
  }

  private static PmartException insufficient(List<Shortage> shortages) {
    List<PmartException.Detail> details =
        shortages.stream()
            .map(
                s ->
                    new PmartException.Detail(
                        s.sku(),
                        "requestedQty=" + s.requestedQty() + ";availableQty=" + s.availableQty()))
            .toList();
    return new PmartException(
        ErrorCode.INSUFFICIENT_STOCK, shortages.size() + "개 SKU 의 가용 재고가 부족합니다.", details);
  }

  // ---------- 확정 ----------

  public CommandResponse confirm(
      String requestId,
      String sourceSystem,
      String reservationId,
      String orderId,
      @Nullable String paymentAttemptId,
      @Nullable Integer expectedVersion) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_CONFIRM),
            new ConfirmRequest(reservationId, orderId, paymentAttemptId, expectedVersion),
            CommandResponse.class,
            () -> {
              ReservationRow r = lockOrThrow(reservationId);
              if (!r.orderId().equals(orderId)) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 다른 주문(" + r.orderId() + ")의 예약입니다.");
              }
              Instant at = clock.instant();
              Transition t = r.state().confirm();
              if (t == Transition.INVALID) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 " + r.state() + " 상태라 확정할 수 없습니다.");
              }
              if (t == Transition.ALREADY) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    reservationId,
                    0,
                    r.version(),
                    false,
                    null,
                    at);
              }
              checkVersion(r, expectedVersion);
              Map<String, Integer> lines = reservations.lines(reservationId);
              int total = lines.values().stream().mapToInt(Integer::intValue).sum();
              int version = r.version() + 1;
              reservations.transition(reservationId, ReservationState.CONFIRMED, null, version, at);
              newCommit("CONFIRM", r.mfcId(), requestId, sourceSystem, reservationId, total, at);
              log.info("reservation {} CONFIRMED (order {})", reservationId, orderId);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, version, false, null, at);
            })
        .value();
  }

  // ---------- 해제 ----------

  public CommandResponse release(
      String requestId,
      String sourceSystem,
      String reservationId,
      String reason,
      @Nullable String sourceRef,
      @Nullable List<Line> lines,
      @Nullable Integer expectedVersion) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_RELEASE),
            new ReleaseRequest(reservationId, reason, sourceRef, lines, expectedVersion),
            CommandResponse.class,
            () -> {
              ReservationRow r = lockOrThrow(reservationId);
              Instant at = clock.instant();
              if (r.state().release() == Transition.ALREADY) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    reservationId,
                    0,
                    r.version(),
                    false,
                    null,
                    at);
              }
              Map<String, Integer> held = reservations.lines(reservationId);
              if (!StockRules.isFullRelease(lines, held)) {
                throw new PmartException(
                    PARTIAL_RELEASE_NOT_SUPPORTED,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "부분 해제는 아직 지원하지 않습니다. lines 를 비우거나 예약 전량을 지정하세요.",
                    false,
                    List.of());
              }
              checkVersion(r, expectedVersion);
              int version = r.version() + 1;
              int total =
                  unreserve(
                      r,
                      held,
                      ReservationState.RELEASED,
                      reason,
                      version,
                      requestId,
                      sourceSystem,
                      at);
              log.info("reservation {} RELEASED ({}, {} units)", reservationId, reason, total);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, version, false, null, at);
            })
        .value();
  }

  // ---------- 만료(주기 작업이 트랜잭션 안에서 호출) ----------

  /**
   * 만료 시각이 지난 HELD 예약 일부를 EXPIRED 로 바꾸고 InventoryHoldExpired 를 Outbox 에 쓴다.
   *
   * @return 처리한 예약 수
   */
  @Transactional
  public int expireDue(int limit) {
    Instant now = clock.instant();
    List<ReservationRow> due = reservations.lockExpired(now, limit);
    for (ReservationRow r : due) {
      Map<String, Integer> held = reservations.lines(r.reservationId());
      int version = r.version() + 1;
      int total =
          unreserve(
              r, held, ReservationState.EXPIRED, "TTL", version, null, events.sourceSystem(), now);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("reservationId", r.reservationId());
      payload.put("orderId", r.orderId());
      payload.put("mfcId", r.mfcId());
      payload.put("expiredAt", now.toString());
      EventEnvelope envelope =
          codec.create(
              events.sourceSystem(),
              "InventoryHoldExpired",
              1,
              "Reservation",
              r.reservationId(),
              version,
              new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
              EventEnvelope.EventSource.REAL,
              payload);
      events.publish(envelope, TOPIC);
      log.info(
          "reservation {} EXPIRED (order {}, {} units, expiresAt {})",
          r.reservationId(),
          r.orderId(),
          total,
          r.expiresAt());
    }
    return due.size();
  }

  /** 잔량 행을 SKU 오름차순으로 잠그고 R−q, 예약 상태 전이, 확정 단위 기록. */
  private int unreserve(
      ReservationRow r,
      Map<String, Integer> held,
      ReservationState to,
      String reason,
      int version,
      @Nullable String requestId,
      String sourceSystem,
      Instant at) {
    stock.lockBalances(r.mfcId(), held.keySet());
    int total = held.values().stream().mapToInt(Integer::intValue).sum();
    reservations.transition(r.reservationId(), to, reason, version, at);
    String commitId =
        newCommit(
            to == ReservationState.EXPIRED ? "EXPIRE" : "RELEASE",
            r.mfcId(),
            requestId,
            sourceSystem,
            r.reservationId(),
            total,
            at);
    held.forEach(
        (sku, qty) -> {
          long v = stock.addReserved(r.mfcId(), sku, -qty, at);
          stock.insertEntry(commitId, r.mfcId(), new EntryRow(sku, "R", -qty, v));
        });
    return total;
  }

  // ---------- 조회 ----------

  @Transactional(readOnly = true)
  public ReservationView get(String reservationId) {
    ReservationRow r =
        reservations
            .find(reservationId)
            .orElseThrow(
                () -> PmartException.notFound(RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
    return new ReservationView(r, reservations.lines(reservationId));
  }

  private ReservationRow lockOrThrow(String reservationId) {
    return reservations
        .lock(reservationId)
        .orElseThrow(
            () -> PmartException.notFound(RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
  }

  private static void checkVersion(ReservationRow r, @Nullable Integer expectedVersion) {
    if (expectedVersion != null && expectedVersion != r.version()) {
      throw PmartException.versionConflict(
          "예약이 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + r.version() + ").");
    }
  }

  private String newCommit(
      String type,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      String sourceRef,
      int qty,
      Instant at) {
    String commitId = "CMT-" + UUID.randomUUID();
    stock.insertCommit(
        new CommitRow(commitId, type, mfcId, requestId, sourceSystem, sourceRef, qty, at));
    return commitId;
  }
}
