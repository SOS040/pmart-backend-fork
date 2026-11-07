package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PickPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 인계 전기(IV-08 인계, ADR-0047). mfc-fulfillment 가 인계 허가(fence) 뒤 부른다. 한 트랜잭션: 예약 행 → 조각 잠금 → 포장
 * 위치의 NORMAL·PACKED 단위 −q(H−q, E−q) + 같은 줄·LOT 확정 조각 R−q(handed +q, 다 넘기면 CONSUMED) →
 * PackageHandedOverPosted. A 는 그대로. 같은 packageId 는 한 번 — 같은 fence 면 처음 commitId, 다른 fence 면 409
 * FENCE_VERSION_MISMATCH.
 */
@Service
public class HandoverPostingService {

  public static final String TOPIC = "mfc.inventory.v1";
  public static final String FENCE_VERSION_MISMATCH = "FENCE_VERSION_MISMATCH";
  public static final String HANDOVER_QTY_EXCEEDED = "HANDOVER_QTY_EXCEEDED";

  private static final Logger log = LoggerFactory.getLogger(HandoverPostingService.class);

  public record Line(String orderLineId, String sku, String lotId, int qty) {}

  public record Posting(
      String packageId,
      String handoverId,
      String handoverAuthorizationId,
      int fenceVersion,
      String mfcId,
      String orderId,
      String reservationId,
      @Nullable String shipmentId,
      @Nullable String deliveryId,
      @Nullable String riderId,
      String locationCode,
      List<Line> lines) {}

  public record Result(String commitId, int qty, boolean replayed) {}

  private final ReservationStore reservations;
  private final Ledger ledger;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public HandoverPostingService(
      ReservationStore reservations,
      Ledger ledger,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.reservations = reservations;
    this.ledger = ledger;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  @Transactional
  public Result post(String requestId, String sourceSystem, Posting in) {
    if (in.lines().isEmpty() || in.lines().stream().anyMatch(l -> l.qty() < 1)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lines 는 1개 이상, qty 는 1 이상입니다.");
    }
    String hash = StockRules.sha256(mapper.writeValueAsString(in));
    Optional<Result> done = existing(in, hash);
    if (done.isPresent()) {
      return done.get();
    }
    ReservationRow r =
        reservations
            .lock(in.reservationId())
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        ReservationService.RESERVATION_NOT_FOUND,
                        "예약이 없습니다: " + in.reservationId()));
    done = existing(in, hash); // 잠금 뒤 다시(경합한 같은 포장)
    if (done.isPresent()) {
      return done.get();
    }
    if (!r.mfcId().equals(in.mfcId()) || !r.orderId().equals(in.orderId())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "다른 센터·주문의 예약입니다.");
    }
    if (r.state() != ReservationState.CONFIRMED) {
      throw PmartException.invalidState(
          "예약 " + r.reservationId() + " 는 " + r.state() + " 상태라 인계할 수 없습니다.");
    }
    // 줄·LOT 마다 확정 조각의 (포장 − 인계) 안에서 나눠 넘긴다
    List<PickPart> parts = reservations.pickParts(r.reservationId(), true);
    Map<String, Integer> handedNow = handed(r.reservationId());
    List<Change> units = new ArrayList<>();
    List<ReservedChange> reserved = new ArrayList<>();
    Map<String, Integer> partAdds = new LinkedHashMap<>();
    int total = 0;
    for (Line l : in.lines()) {
      int need = l.qty();
      for (PickPart p : parts) {
        if (need == 0) {
          break;
        }
        if (p.state() != ReservationState.CONFIRMED
            || !l.orderLineId().equals(p.orderLineId())
            || !l.lotId().equals(p.lotId())
            || !l.sku().equals(p.sku())) {
          continue;
        }
        int room =
            p.packedQty()
                - handedNow.getOrDefault(p.partId(), 0)
                - partAdds.getOrDefault(p.partId(), 0);
        int take = Math.min(room, need);
        if (take <= 0) {
          continue;
        }
        partAdds.merge(p.partId(), take, Integer::sum);
        need -= take;
      }
      if (need > 0) {
        throw new PmartException(
            HANDOVER_QTY_EXCEEDED,
            HttpStatus.CONFLICT,
            "주문 줄 "
                + l.orderLineId()
                + " LOT "
                + l.lotId()
                + ": 포장했지만 아직 인계 안 한 양보다 "
                + need
                + "개 많습니다.",
            false,
            List.of());
      }
      units.add(
          new Change(
              new Key(in.locationCode(), l.sku(), l.lotId(), Quality.NORMAL, Work.PACKED),
              -l.qty()));
      reserved.add(new ReservedChange(l.sku(), l.lotId(), -l.qty()));
      total += l.qty();
    }
    Instant at = clock.instant();
    String commitId =
        ledger.post(
            "HANDOVER",
            r.mfcId(),
            requestId,
            sourceSystem,
            in.packageId(),
            total,
            units,
            reserved,
            at);
    for (var e : partAdds.entrySet()) {
      jdbc.update(
          "UPDATE reservation_part SET handed_qty = handed_qty + ?,"
              + " state = CASE WHEN handed_qty + ? = qty THEN 'CONSUMED' ELSE state END,"
              + " updated_at = ? WHERE part_id = ?",
          e.getValue(),
          e.getValue(),
          Timestamp.from(at),
          e.getKey());
    }
    boolean anyOpen =
        reservations.parts(r.reservationId()).stream().anyMatch(p -> p.state().holdsStock());
    if (!anyOpen) {
      reservations.transition(
          r.reservationId(), ReservationState.CONSUMED, null, r.version() + 1, at);
    }
    jdbc.update(
        "INSERT INTO package_handover_posting (package_id, handover_id, handover_authorization_id,"
            + " fence_version, reservation_id, mfc_id, commit_id, request_hash, qty, lines, posted_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)",
        in.packageId(),
        in.handoverId(),
        in.handoverAuthorizationId(),
        in.fenceVersion(),
        r.reservationId(),
        r.mfcId(),
        commitId,
        hash,
        total,
        mapper.writeValueAsString(in.lines()),
        Timestamp.from(at));
    publish(in, r, commitId, at);
    log.info(
        "포장 {} 인계 전기 {} — {}개 (예약 {}{})",
        in.packageId(),
        commitId,
        total,
        r.reservationId(),
        anyOpen ? "" : ", 소진");
    return new Result(commitId, total, false);
  }

  /** 같은 포장이 이미 전기됐으면 결과. 같은 fence 가 아니면 409. */
  private Optional<Result> existing(Posting in, String hash) {
    return jdbc
        .query(
            "SELECT commit_id, qty, handover_authorization_id, fence_version FROM"
                + " package_handover_posting WHERE package_id = ?",
            (rs, i) -> {
              if (!rs.getString("handover_authorization_id").equals(in.handoverAuthorizationId())
                  || rs.getInt("fence_version") != in.fenceVersion()) {
                throw new PmartException(
                    FENCE_VERSION_MISMATCH,
                    HttpStatus.CONFLICT,
                    "포장 " + in.packageId() + " 은 다른 인계 허가로 이미 인계됐습니다.",
                    false,
                    List.of());
              }
              return new Result(rs.getString("commit_id"), rs.getInt("qty"), true);
            },
            in.packageId())
        .stream()
        .findFirst();
  }

  private Map<String, Integer> handed(String reservationId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT part_id, handed_qty FROM reservation_part WHERE reservation_id = ?",
        rs -> {
          out.put(rs.getString("part_id"), rs.getInt("handed_qty"));
        },
        reservationId);
    return out;
  }

  private void publish(Posting in, ReservationRow r, String commitId, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("commitId", commitId);
    p.put("packageId", in.packageId());
    p.put("handoverId", in.handoverId());
    if (in.shipmentId() != null) {
      p.put("shipmentId", in.shipmentId());
    }
    p.put("orderId", r.orderId());
    p.put("reservationId", r.reservationId());
    p.put("mfcId", r.mfcId());
    p.put(
        "lines",
        in.lines().stream()
            .map(l -> Map.of("sku", l.sku(), "lot", l.lotId(), "qty", l.qty()))
            .toList());
    p.put("postedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "PackageHandedOverPosted",
            1,
            "Package",
            in.packageId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p),
        TOPIC);
  }
}
