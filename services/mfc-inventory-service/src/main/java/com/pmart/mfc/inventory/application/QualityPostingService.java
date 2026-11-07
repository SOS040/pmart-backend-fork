package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.LotPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PartRow;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 품질 반영(mfc-quality → inventory, IV-09·IV-10). 승인 근거(approvalRef) 필수, 같은 요청 키는 한 번.
 *
 * <ul>
 *   <li>QUARANTINE: 위치의 NORMAL 단위(기본 STORED, UNSTOWED 가능) → QUARANTINED(같은 업무 상태). STORED 면 E−q.
 *       LOT 의 R 이 남은 E 를 넘으면 그 LOT 의 아직 집지 않은 예약 조각을 **나중에 잡은 예약부터** 줄여 BLOCKED(이행부족 D) +
 *       ReservationBlocked — 자동 재배분은 하지 않는다(MFC Q-13 "다주문 부족 자동 배분 차단, 영향 약속 보류·수동 해결").
 *   <li>RELEASE: QUARANTINED → NORMAL(같은 업무 상태). STORED 면 E+q. UNSTOWED 는 적치 뒤에야 판매 가능.
 *   <li>DISPOSE: QUARANTINED 단위 −q(H−q, Q−q). 승인 잔량은 mfc-quality 가 지킨다(approvalRef = 폐기 승인 ID).
 * </ul>
 */
@Service
public class QualityPostingService {

  static final String OP = "postQualityDecision";

  private static final Logger log = LoggerFactory.getLogger(QualityPostingService.class);

  public record Line(
      String sku, String lotId, String locationCode, int qty, @Nullable String workState) {}

  public record Posting(
      String postingType,
      String mfcId,
      @Nullable String qualityDecisionId,
      String approvalRef,
      @Nullable String reason,
      List<Line> lines) {}

  private final StockStore stock;
  private final ReservationStore reservations;
  private final ReservationService reservationService;
  private final Ledger ledger;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public QualityPostingService(
      StockStore stock,
      ReservationStore reservations,
      ReservationService reservationService,
      Ledger ledger,
      RequestIdempotency idempotency,
      Clock clock) {
    this.stock = stock;
    this.reservations = reservations;
    this.reservationService = reservationService;
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse post(String requestId, String sourceSystem, Posting in) {
    if (in.approvalRef() == null || in.approvalRef().isBlank()) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "승인 근거(approvalRef)가 필요합니다.");
    }
    if (!List.of("QUARANTINE", "RELEASE", "DISPOSE").contains(in.postingType())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "postingType 은 QUARANTINE/RELEASE/DISPOSE 입니다.");
    }
    if (in.lines().isEmpty() || in.lines().size() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "줄은 1~500개입니다.");
    }
    for (Line l : in.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상입니다: " + l.sku());
      }
      Work w = work(l);
      if (w != Work.STORED && w != Work.UNSTOWED) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "workState 는 STORED/UNSTOWED 입니다(집품·포장분은 결품·파손 보고로): " + l.sku());
      }
    }
    int total = in.lines().stream().mapToInt(Line::qty).sum();
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            in,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              Map<String, LotRow> lots =
                  stock.lots(in.lines().stream().map(Line::lotId).collect(Collectors.toSet()));
              List<Change> changes = new ArrayList<>();
              // LOT 별 E 감소(격리) — 예약 영향 계산
              Map<String, Integer> sellableDrop = new TreeMap<>();
              for (Line l : in.lines()) {
                LotRow lot = lots.get(l.lotId());
                if (lot == null || !lot.sku().equals(l.sku())) {
                  throw new PmartException(
                      ErrorCode.VALIDATION_FAILED,
                      "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
                }
                Work w = work(l);
                Key normal = new Key(l.locationCode(), l.sku(), l.lotId(), Quality.NORMAL, w);
                Key quarantined = normal.with(Quality.QUARANTINED, w);
                switch (in.postingType()) {
                  case "QUARANTINE" -> {
                    changes.add(new Change(normal, -l.qty()));
                    changes.add(new Change(quarantined, l.qty()));
                    if (normal.sellable()) {
                      sellableDrop.merge(l.sku() + "|" + l.lotId(), l.qty(), Integer::sum);
                    }
                  }
                  case "RELEASE" -> {
                    changes.add(new Change(quarantined, -l.qty()));
                    changes.add(new Change(normal, l.qty()));
                  }
                  default -> changes.add(new Change(quarantined, -l.qty()));
                }
              }
              List<ReservedChange> reserved = new ArrayList<>();
              List<Blocked> blocked = new ArrayList<>();
              if (!sellableDrop.isEmpty()) {
                block(in, sellableDrop, reserved, blocked, at);
              }
              String commitId =
                  ledger.post(
                      "QUALITY_" + in.postingType(),
                      in.mfcId(),
                      requestId,
                      sourceSystem,
                      in.qualityDecisionId() != null ? in.qualityDecisionId() : in.approvalRef(),
                      total,
                      changes,
                      reserved,
                      at);
              for (Blocked b : blocked) {
                reservationService.publishBlocked(
                    b.reservation(), b.sku(), b.lotId(), b.qty(), "QUALITY_HOLD", at);
              }
              log.info(
                  "품질 반영 {} {} {}개 (근거 {}, 결정 {}) — 예약 보류 {}건",
                  commitId,
                  in.postingType(),
                  total,
                  in.approvalRef(),
                  in.qualityDecisionId(),
                  blocked.size());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  private record Blocked(ReservationRow reservation, String sku, String lotId, int qty) {}

  /**
   * 격리로 LOT 의 R 이 남은 E 를 넘으면 넘는 양만큼 아직 집지 않은 예약 조각을 나중에 잡은 예약부터 BLOCKED 로. 잠금 순서: 예약 행(ID 순) → 조각 →
   * 잔량(Ledger).
   */
  private void block(
      Posting in,
      Map<String, Integer> sellableDrop,
      List<ReservedChange> reserved,
      List<Blocked> blocked,
      Instant at) {
    Map<String, List<LotPart>> candidates = new LinkedHashMap<>();
    TreeSet<String> reservationIds = new TreeSet<>();
    for (String k : sellableDrop.keySet()) {
      String[] sl = k.split("\\|", 2);
      List<LotPart> parts = reservations.activeLotParts(in.mfcId(), sl[0], sl[1]);
      candidates.put(k, parts);
      parts.forEach(p -> reservationIds.add(p.reservationId()));
    }
    Map<String, ReservationRow> locked = new LinkedHashMap<>();
    for (String id : reservationIds) {
      reservations.lock(id).ifPresent(r -> locked.put(id, r));
    }
    for (var e : sellableDrop.entrySet()) {
      String[] sl = e.getKey().split("\\|", 2);
      String sku = sl[0];
      String lotId = sl[1];
      LotBalance lb =
          stock.lockLotBalances(in.mfcId(), List.of(sku)).stream()
              .filter(b -> b.lotId().equals(lotId))
              .findFirst()
              .orElse(null);
      if (lb == null) {
        continue; // 잔량 행이 없으면 Ledger 가 부족으로 거절한다
      }
      int deficit = lb.reserved() - (lb.sellable() - e.getValue());
      if (deficit <= 0) {
        continue;
      }
      // 조각을 다시 읽는다(잠근 뒤 — 사이에 집품·해제가 있었을 수 있다)
      for (LotPart p : reservations.activeLotParts(in.mfcId(), sku, lotId)) {
        if (deficit == 0) {
          break;
        }
        ReservationRow r = locked.get(p.reservationId());
        if (r == null) {
          continue; // 잠그지 못한 새 예약 — 이번에는 건드리지 않는다(남은 부족은 Ledger 가 거절)
        }
        int room = p.qty() - p.pickedQty();
        int take = Math.min(deficit, room);
        if (take <= 0) {
          continue;
        }
        if (p.qty() - take > 0) {
          reservations.shrinkPart(p.partId(), p.qty() - take, at);
        } else {
          reservations.setPartState(
              p.partId(), ReservationState.RELEASED, "QUALITY_HOLD:" + in.approvalRef(), at);
        }
        reservations.insertPart(
            r.reservationId(),
            new PartRow(
                r.reservationId() + ":Q" + UUID.randomUUID().toString().substring(0, 8),
                p.orderLineId(),
                sku,
                lotId,
                "HAND",
                take,
                ReservationState.BLOCKED,
                "QUALITY_HOLD"),
            at);
        reserved.add(new ReservedChange(sku, lotId, -take));
        blocked.add(new Blocked(r, sku, lotId, take));
        deficit -= take;
      }
    }
  }

  private static Work work(Line l) {
    return l.workState() == null ? Work.STORED : Work.valueOf(l.workState());
  }
}
