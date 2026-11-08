package com.pmart.mfc.fulfillment.application;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.HandoverRules;
import com.pmart.mfc.fulfillment.domain.RepackRules;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore.PackageFence;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.PackageRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 재포장(WK-07). 실물 봉투를 바꾸거나 라벨을 못 읽을 때 새 포장(새 ID·packageVersion+1·새 QR)을 발급하고 이전 포장은 INVALIDATED(이전
 * QR 은 인계 허가에서 PACKAGE_INVALIDATED). 내용물·포장 위치는 그대로라 재고(PACKED 단위)·예약은 변하지 않는다 — WK-07 "센터 현물·고객 예약
 * 변동 없음". 단순 라벨 재출력은 이 요청이 아니다(기존 QR 그대로, 서버 기록 없음).
 *
 * <p>주문 잠금 아래에서 처리해 중지·인계 허가와 한 줄. 인계된 포장·살아 있는 fence·결과 대기 인계·중지 기록이 있는 주문은 거절. 같은 Idempotency-Key
 * 는 같은 새 포장.
 */
@Service
public class RepackService {

  private static final Logger log = LoggerFactory.getLogger(RepackService.class);
  private static final TypeReference<List<Content>> CONTENTS = new TypeReference<>() {};

  public record RepackCommand(String reason, @Nullable List<ContentRequest> contents) {}

  private final TaskStore store;
  private final HandoverStore handovers;
  private final WorkOrderStore works;
  private final WorkStopStore stops;
  private final FulfillmentEvents events;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RepackService(
      TaskStore store,
      HandoverStore handovers,
      WorkOrderStore works,
      WorkStopStore stops,
      FulfillmentEvents events,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.store = store;
    this.handovers = handovers;
    this.works = works;
    this.stops = stops;
    this.events = events;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  public record RegroupCommand(String reason, List<String> packageIds, List<NewPackage> packages) {}

  public record NewPackage(int bagCount, List<ContentRequest> contents) {}

  /**
   * 포장 사이 재구성(C-12 1부, 0.4.7): 같은 출고·같은 포장 위치의 밀봉 포장들 → 새 포장들, 합계 그대로(재고·예약 변화 없음). 이전 포장마다 단건 재포장과
   * 같은 거절 규칙. 같은 요청은 같은 새 포장들(request_id = 요청:번호).
   */
  public List<PackageRow> regroup(String shipmentId, String requestId, RegroupCommand cmd) {
    if (!RepackRules.REASONS.contains(cmd.reason())) {
      throw TaskService.invalid("reason 은 " + RepackRules.REASONS + " 중 하나입니다.");
    }
    if (cmd.packageIds().isEmpty() || cmd.packages().isEmpty()) {
      throw TaskService.invalid("packageIds·packages 가 필요합니다.");
    }
    PackageFence first =
        handovers
            .find(cmd.packageIds().get(0))
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "PACKAGE_NOT_FOUND", "포장이 없습니다: " + cmd.packageIds().get(0)));
    String me = TaskService.requireWorker(first.mfcId());
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              var done = store.findPackage(shipmentId, requestId + ":1");
              if (done.isPresent()) {
                List<PackageRow> again = new java.util.ArrayList<>();
                for (int i = 1; i <= cmd.packages().size(); i++) {
                  store.findPackage(shipmentId, requestId + ":" + i).ifPresent(again::add);
                }
                return again;
              }
              works.lockOrder(first.orderId());
              Instant now = clock.instant();
              List<PackageFence> olds = new java.util.ArrayList<>();
              List<Content> current = new java.util.ArrayList<>();
              String location = null;
              int maxVersion = 0;
              boolean alcohol = false;
              for (String id : new java.util.LinkedHashSet<>(cmd.packageIds())) {
                PackageFence p =
                    handovers
                        .lock(id)
                        .orElseThrow(
                            () -> PmartException.notFound("PACKAGE_NOT_FOUND", "포장이 없습니다: " + id));
                if (!shipmentId.equals(p.shipmentId())) {
                  throw mismatch("포장 " + id + " 는 이 출고(" + shipmentId + ")의 포장이 아닙니다.");
                }
                boolean requested = handovers.handover(id).isPresent();
                boolean live =
                    HandoverRules.fenceLive(p.fenceState(), p.fenceExpiresAt(), requested, now);
                String reject =
                    RepackRules.rejection(
                        p.state(),
                        live,
                        "HANDOVER_IN_PROGRESS".equals(p.fenceState()),
                        requested,
                        stops.orderStopped(p.orderId()));
                if (reject != null) {
                  throw new PmartException(
                      reject,
                      HttpStatus.CONFLICT,
                      RepackRules.message(reject, p.state()) + " (" + id + ")",
                      false,
                      List.of());
                }
                PackageRow row = store.pkg(id).orElseThrow();
                if (location == null) {
                  location = row.packingLocation();
                } else if (!java.util.Objects.equals(location, row.packingLocation())) {
                  throw mismatch(
                      "포장 위치가 다른 포장은 함께 재구성하지 않습니다("
                          + location
                          + ", "
                          + row.packingLocation()
                          + ").");
                }
                maxVersion = Math.max(maxVersion, row.packageVersion());
                alcohol |= row.containsAlcohol();
                current.addAll(mapper.readValue(p.contents(), CONTENTS));
                olds.add(p);
              }
              String bad =
                  RepackRules.regroupMismatch(
                      current, cmd.packages().stream().map(NewPackage::contents).toList());
              if (bad != null) {
                throw mismatch(bad);
              }
              PackageRow template = store.pkg(olds.get(0).packageId()).orElseThrow();
              List<String> newIds = new java.util.ArrayList<>();
              int n = 0;
              for (NewPackage np : cmd.packages()) {
                n++;
                String newId = "PKG-" + UUID.randomUUID();
                int ver = maxVersion + 1;
                List<Content> contents =
                    np.contents().stream()
                        .map(
                            c ->
                                new Content(
                                    c.orderLineId(),
                                    c.sku(),
                                    Objects.requireNonNull(c.lotId()),
                                    c.qty()))
                        .toList();
                store.insertRegroup(
                    template,
                    newId,
                    requestId + ":" + n,
                    ver,
                    "PKG:" + newId + ":" + ver,
                    mapper.writeValueAsString(contents),
                    Math.max(1, np.bagCount()),
                    alcohol,
                    me,
                    now);
                newIds.add(newId);
              }
              for (PackageFence p : olds) {
                store.invalidateForRepack(
                    p.packageId(), cmd.reason() + "→" + String.join(",", newIds));
                events.packageInvalidated(p, newIds, cmd.reason(), me, now);
              }
              log.info(
                  "출고 {} 포장 재구성({}) {} → {}", shipmentId, cmd.reason(), cmd.packageIds(), newIds);
              return newIds.stream().map(id -> store.pkg(id).orElseThrow()).toList();
            }));
  }

  private static PmartException mismatch(String msg) {
    return new PmartException(
        "CONTENT_MISMATCH", HttpStatus.UNPROCESSABLE_CONTENT, msg, false, List.of());
  }

  public PackageRow repack(String packageId, String requestId, RepackCommand cmd) {
    PackageFence p0 =
        handovers
            .find(packageId)
            .orElseThrow(
                () -> PmartException.notFound("PACKAGE_NOT_FOUND", "포장이 없습니다: " + packageId));
    String me = TaskService.requireWorker(p0.mfcId());
    if (!RepackRules.REASONS.contains(cmd.reason())) {
      throw TaskService.invalid("reason 은 " + RepackRules.REASONS + " 중 하나입니다.");
    }
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              var done = store.findPackage(p0.shipmentId(), requestId);
              if (done.isPresent()) {
                return done.get(); // 같은 요청 — 같은 새 포장
              }
              works.lockOrder(p0.orderId());
              PackageFence p = handovers.lock(packageId).orElseThrow();
              Instant now = clock.instant();
              boolean handoverRequested = handovers.handover(packageId).isPresent();
              boolean live =
                  HandoverRules.fenceLive(
                      p.fenceState(), p.fenceExpiresAt(), handoverRequested, now);
              String reject =
                  RepackRules.rejection(
                      p.state(),
                      live,
                      "HANDOVER_IN_PROGRESS".equals(p.fenceState()),
                      handoverRequested,
                      stops.orderStopped(p.orderId()));
              if (reject != null) {
                throw new PmartException(
                    reject,
                    HttpStatus.CONFLICT,
                    RepackRules.message(reject, p.state()),
                    false,
                    List.of());
              }
              List<Content> current = mapper.readValue(p.contents(), CONTENTS);
              String mismatch = RepackRules.contentMismatch(current, cmd.contents());
              if (mismatch != null) {
                throw new PmartException(
                    "CONTENT_MISMATCH",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    mismatch,
                    false,
                    List.of());
              }
              PackageRow old = store.pkg(packageId).orElseThrow();
              String newId = "PKG-" + UUID.randomUUID();
              int newVersion = old.packageVersion() + 1;
              String qr = "PKG:" + newId + ":" + newVersion;
              store.insertRepack(old, newId, requestId, newVersion, qr, me, now);
              store.invalidateForRepack(packageId, cmd.reason() + "→" + newId);
              events.packageInvalidated(p, newId, cmd.reason(), me, now);
              log.info("포장 {} 재포장({}) → {} (주문 {})", packageId, cmd.reason(), newId, p.orderId());
              return store.pkg(newId).orElseThrow();
            }));
  }
}
