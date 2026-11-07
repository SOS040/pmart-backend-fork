package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.LotAllocator;
import com.pmart.mfc.inventory.domain.LotAllocator.Allocation;
import com.pmart.mfc.inventory.domain.LotAllocator.LineRequest;
import com.pmart.mfc.inventory.domain.LotAllocator.LineShortage;
import com.pmart.mfc.inventory.domain.LotAllocator.LotCandidate;
import com.pmart.mfc.inventory.domain.LotAllocator.ShelfRule;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.ReservationState.Transition;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import com.pmart.mfc.inventory.domain.StockRules.Shortage;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PartRow;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
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
 * 재고 예약(S1 a, IV-05, ADR-0040 결정 4·5): 예약 → 확정 / 해제 / 만료. 모든 명령은 {@link RequestIdempotency} 안에서 한
 * 트랜잭션이다.
 *
 * <ul>
 *   <li>예약: 같은 SKU 를 합산하고, 취급 OFF 면 SKU_NOT_HANDLED, 센터 요약을 SKU 오름차순으로 잠근 뒤 한 SKU 라도 A 가 모자라면
 *       INSUFFICIENT_STOCK 으로 전체 거절(TC-09). 그다음 LOT 요약을 잠그고 줄마다 적격 LOT 을 FEFO 로 배정한다(줄당 LOT 수 제한
 *       없음, 최소 잔여일 — 배송 약속일 기준). 적격 LOT 이 모자란 줄이 있으면 전체 거절(사유 NO_ELIGIBLE_LOT). 거절은 예외라 멱등 기록에 남지
 *       않는다 — 같은 키로 다시 보내면 그때 잔량으로 다시 판정한다.
 *   <li>잠금 순서는 예약 행 → 센터 요약(SKU 오름차순) → LOT 요약. 만료 작업도 같은 순서라 교착이 없다.
 *   <li>확정은 R 총량 불변, 해제·만료는 조각마다 R−q.
 * </ul>
 */
@Service
public class ReservationService {

  public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
  public static final String PARTIAL_RELEASE_NOT_SUPPORTED = "PARTIAL_RELEASE_NOT_SUPPORTED";
  public static final String NO_ELIGIBLE_LOT = "NO_ELIGIBLE_LOT";

  /** 계약 InventoryHoldExpired.v1 의 토픽(집합 이름이 아니라 서비스 토픽). */
  public static final String TOPIC = "mfc.inventory.v1";

  /** 날짜 판정은 Asia/Seoul(ADR-0010). */
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

  static final String OP_RESERVE = "reserveInventory";
  static final String OP_CONFIRM = "confirmReservation";
  static final String OP_RELEASE = "releaseReservation";

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  /** 예약 줄. */
  public record ReserveLine(@Nullable String orderLineId, String sku, int qty) {}

  /** 예약 명령. lotConstraint 는 아직 받지 않는다(api 가 400). promisedDate 가 없으면 예약 날짜(즉시 배송). */
  public record ReserveCommand(
      String orderId,
      String mfcId,
      @Nullable Integer ttlMinutes,
      @Nullable LocalDate promisedDate,
      List<ReserveLine> lines) {}

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
  public record ReservationView(ReservationRow row, List<PartRow> parts) {}

  private final ReservationStore reservations;
  private final StockStore stock;
  private final Ledger ledger;
  private final SkuHandlingStore handlings;
  private final SkuProjectionStore skus;
  private final InventoryProperties properties;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public ReservationService(
      ReservationStore reservations,
      StockStore stock,
      Ledger ledger,
      SkuHandlingStore handlings,
      SkuProjectionStore skus,
      InventoryProperties properties,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.reservations = reservations;
    this.stock = stock;
    this.ledger = ledger;
    this.handlings = handlings;
    this.skus = skus;
    this.properties = properties;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  // ---------- 예약 ----------

  public CommandResponse reserve(String requestId, String sourceSystem, ReserveCommand cmd) {
    SortedMap<String, Integer> requested;
    try {
      requested =
          StockRules.sumBySku(cmd.lines().stream().map(l -> new Line(l.sku(), l.qty())).toList());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    if (requested.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lines 가 비어 있습니다.");
    }
    int ttl = cmd.ttlMinutes() == null ? defaultTtlMinutes() : cmd.ttlMinutes();
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
              LocalDate promised =
                  cmd.promisedDate() != null
                      ? cmd.promisedDate()
                      : LocalDate.ofInstant(at, BUSINESS_ZONE);
              List<LotCandidate> lots = new ArrayList<>();
              for (LotBalance b : stock.lockLotBalances(cmd.mfcId(), requested.keySet())) {
                lots.add(
                    new LotCandidate(
                        b.sku(),
                        b.lotId(),
                        b.expiryDate(),
                        b.manufactureDate(),
                        b.firstNormalAt(),
                        b.available()));
              }
              Map<String, SkuProjection> projections = skus.findAll(requested.keySet());
              LotAllocator.Rules rules = shelfRules(requested.keySet(), projections);
              List<String> missing = LotAllocator.missingPolicy(lots, rules);
              if (!missing.isEmpty()) {
                throw PmartException.configRequired(
                    "pmart.inventory.min-remaining-days(보관 유형 "
                        + storageTypes(missing, projections)
                        + ", SKU "
                        + missing
                        + ")");
              }
              LotAllocator.Result result =
                  LotAllocator.allocate(
                      cmd.lines().stream()
                          .map(l -> new LineRequest(l.orderLineId(), l.sku(), l.qty()))
                          .toList(),
                      lots,
                      promised,
                      rules);
              if (!result.shortages().isEmpty()) {
                throw noEligibleLot(result.shortages());
              }
              String reservationId = "RSV-" + UUID.randomUUID();
              int total = requested.values().stream().mapToInt(Integer::intValue).sum();
              reservations.insert(
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  at.plus(Duration.ofMinutes(ttl)),
                  cmd.promisedDate(),
                  sourceSystem,
                  requestId,
                  at);
              List<ReservedChange> deltas = new ArrayList<>();
              int n = 0;
              for (Allocation al : result.allocations()) {
                n++;
                reservations.insertPart(
                    reservationId,
                    new PartRow(
                        reservationId + ":" + n,
                        al.orderLineId(),
                        al.sku(),
                        al.lotId(),
                        "HAND",
                        al.qty(),
                        ReservationState.HELD,
                        null),
                    at);
                deltas.add(new ReservedChange(al.sku(), al.lotId(), al.qty()));
              }
              ledger.post(
                  "RESERVE",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  reservationId,
                  total,
                  List.of(),
                  deltas,
                  at);
              log.info(
                  "reservation {} HELD for order {} at {} ({} units in {} parts, ttl {}m)",
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  total,
                  n,
                  ttl);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, 1, false, null, at);
            })
        .value();
  }

  /** 요청에 유효시간이 없을 때(Q-04 결정 2026-11-07: 10분, ADR-0042). 설정이 없으면 CONFIG_REQUIRED. */
  private int defaultTtlMinutes() {
    Duration d = properties.getDefaultHoldTtl();
    if (d == null || d.toMinutes() < 1) {
      throw PmartException.configRequired("pmart.inventory.default-hold-ttl(예약 유효시간)");
    }
    return (int) d.toMinutes();
  }

  /**
   * SKU → 소비기한 기준(ADR-0041). 최소 잔여일은 상품 보장(shelfMinRemainingDays)을 먼저 쓰고, 없으면 보관 유형 기본값 ({@code
   * pmart.inventory.min-remaining-days}). 둘 다 없으면 미설정(null — 기한 있는 LOT 은 CONFIG_REQUIRED). 제조 후
   * 경과일은 상품 보장만.
   */
  private LotAllocator.Rules shelfRules(
      Collection<String> skuIds, Map<String, SkuProjection> projections) {
    Map<String, ShelfRule> out = new LinkedHashMap<>();
    for (String sku : skuIds) {
      SkuProjection p = projections.get(sku);
      if (p == null) {
        continue;
      }
      Integer storageDefault =
          p.storageType() == null ? null : properties.getMinRemainingDays().get(p.storageType());
      boolean guaranteed = p.shelfMinRemainingDays() != null;
      out.put(
          sku,
          new ShelfRule(
              guaranteed ? p.shelfMinRemainingDays() : storageDefault,
              guaranteed,
              p.shelfMaxDaysSinceManufacture()));
    }
    return sku -> out.getOrDefault(sku, ShelfRule.storageDefault(null));
  }

  private static String storageTypes(List<String> skuIds, Map<String, SkuProjection> projections) {
    return skuIds.stream()
        .map(s -> projections.get(s) == null ? null : projections.get(s).storageType())
        .map(t -> t == null ? "미상" : t)
        .distinct()
        .sorted()
        .toList()
        .toString();
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

  private static PmartException noEligibleLot(List<LineShortage> shortages) {
    List<PmartException.Detail> details =
        shortages.stream()
            .map(
                s ->
                    new PmartException.Detail(
                        s.sku(),
                        NO_ELIGIBLE_LOT
                            + ";orderLineId="
                            + s.orderLineId()
                            + ";requestedQty="
                            + s.requestedQty()
                            + ";eligibleQty="
                            + s.eligibleQty()))
            .toList();
    return new PmartException(
        ErrorCode.INSUFFICIENT_STOCK, shortages.size() + "개 줄에 소비기한 기준을 통과한 재고가 부족합니다.", details);
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
              int total =
                  reservations.heldBySku(reservationId).values().stream()
                      .mapToInt(Integer::intValue)
                      .sum();
              int version = r.version() + 1;
              reservations.transition(reservationId, ReservationState.CONFIRMED, null, version, at);
              reservations.transitionParts(
                  reservationId, ReservationState.HELD, ReservationState.CONFIRMED, at);
              ledger.record(
                  "CONFIRM", r.mfcId(), requestId, sourceSystem, reservationId, total, at);
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
              Transition rt = r.state().release();
              if (rt == Transition.INVALID) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 " + r.state() + " 상태라 해제할 수 없습니다.");
              }
              if (rt == Transition.ALREADY) {
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
              Map<String, Integer> held = reservations.heldBySku(reservationId);
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
                      r, ReservationState.RELEASED, reason, version, requestId, sourceSystem, at);
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
      int version = r.version() + 1;
      int total =
          unreserve(r, ReservationState.EXPIRED, "TTL", version, null, events.sourceSystem(), now);
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

  /** 현물 점유 조각(HELD·CONFIRMED)을 to 로 바꾸고 LOT·센터 요약에서 R−q, 확정 단위 기록. */
  private int unreserve(
      ReservationRow r,
      ReservationState to,
      String reason,
      int version,
      @Nullable String requestId,
      String sourceSystem,
      Instant at) {
    List<ReservedChange> deltas = new ArrayList<>();
    int total = 0;
    for (PartRow p : reservations.parts(r.reservationId())) {
      if (p.state().holdsStock()) {
        deltas.add(new ReservedChange(p.sku(), p.lotId(), -p.qty()));
        total += p.qty();
      }
    }
    ledger.post(
        to == ReservationState.EXPIRED ? "EXPIRE" : "RELEASE",
        r.mfcId(),
        requestId,
        sourceSystem,
        r.reservationId(),
        total,
        List.of(),
        deltas,
        at);
    reservations.transition(r.reservationId(), to, reason, version, at);
    reservations.transitionParts(r.reservationId(), ReservationState.HELD, to, at);
    reservations.transitionParts(r.reservationId(), ReservationState.CONFIRMED, to, at);
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
    return new ReservationView(r, reservations.parts(reservationId));
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
}
