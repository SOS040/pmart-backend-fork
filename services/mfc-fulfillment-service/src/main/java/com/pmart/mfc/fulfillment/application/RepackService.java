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
