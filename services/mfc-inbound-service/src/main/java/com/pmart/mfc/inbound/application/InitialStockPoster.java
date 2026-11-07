package com.pmart.mfc.inbound.application;

import com.pmart.mfc.inbound.domain.InitialStockRules;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore.LoadRow;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.Receipt;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.ReceiptLine;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PENDING 초기 보유 반입을 mfc-inventory 에 전기한다(주기 작업). 선점(짧은 트랜잭션) → HTTP(트랜잭션 밖) → 결과 기록 순서다. inventory
 * 가 내려가 있거나 응답이 불명이면 같은 receiptId 로 점점 길게 기다렸다 다시 보낸다(inventory 가 receiptId 로 중복 계상을 막음).
 */
@Component
class InitialStockPoster {

  static final int BATCH = 20;

  /** 선점 lease — HTTP 응답 대기(기본 5초)보다 충분히 길게. 프로세스가 죽으면 이 시간 뒤 다른 인스턴스가 다시 잡는다. */
  static final Duration LEASE = Duration.ofSeconds(60);

  static final String TRF_PREFIX = "INITIAL-";
  static final String DEPARTURE = "INITIAL";

  private static final Logger log = LoggerFactory.getLogger(InitialStockPoster.class);

  private final InitialStockStore store;
  private final InboundTaskStore tasks;
  private final InventoryReceiptClient inventory;
  private final TransactionTemplate tx;
  private final Clock clock;

  InitialStockPoster(
      InitialStockStore store,
      InboundTaskStore tasks,
      InventoryReceiptClient inventory,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.tasks = tasks;
    this.inventory = inventory;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inbound.post-interval:PT3S}",
      initialDelayString = "${pmart.inbound.post-interval:PT3S}")
  void postDue() {
    try {
      Instant now = clock.instant();
      List<String> claimed = tx.execute(s -> store.claimDue(now, now.plus(LEASE), BATCH));
      if (claimed == null) {
        return;
      }
      for (String receiptId : claimed) {
        store.find(receiptId).ifPresent(this::post);
      }
    } catch (RuntimeException e) {
      // DB 일시 장애 등 — 다음 주기에 다시 본다
      log.warn("initial stock posting failed: {}", e.toString());
    }
  }

  private void post(LoadRow load) {
    if (load.mfcId() == null
        || load.receivingLocation() == null
        || load.received().values().stream().noneMatch(q -> q > 0)) {
      store.markRejected(load.receiptId(), "INVALID: 센터·행 없음", clock.instant());
      return;
    }
    Result r =
        inventory.post(
            new Receipt(
                load.receiptId(),
                TRF_PREFIX + load.approvalId(),
                DEPARTURE,
                load.mfcId(),
                load.createdAt(),
                load.receivingLocation(),
                positive(load.received())));
    Instant at = clock.instant();
    switch (r) {
      case Result.Posted p -> {
        List<ReceiptLine> received;
        try {
          received = inventory.receiptLines(load.receiptId());
        } catch (RuntimeException e) {
          Duration wait = InitialStockRules.backoff(load.attempts());
          store.markRetry(load.receiptId(), "수령 결과 조회 실패: " + e.getMessage(), at.plus(wait), at);
          return;
        }
        tx.executeWithoutResult(
            st -> {
              store.markApplied(load.receiptId(), p.commitId(), at);
              tasks.inspectionsFor(load.receiptId(), load.mfcId(), received, at);
            });
        log.info(
            "initial stock {} (approval {}) APPLIED to {} as {}",
            load.receiptId(),
            load.approvalId(),
            load.mfcId(),
            p.commitId());
      }
      case Result.Rejected j -> {
        store.markRejected(load.receiptId(), j.reason(), at);
        log.warn("initial stock {} REJECTED by inventory: {}", load.receiptId(), j.reason());
      }
      case Result.Retry t -> {
        Duration wait = InitialStockRules.backoff(load.attempts());
        store.markRetry(load.receiptId(), t.error(), at.plus(wait), at);
        log.warn(
            "initial stock {} attempt {} failed, retry in {}: {}",
            load.receiptId(),
            load.attempts(),
            wait,
            t.error());
      }
    }
  }

  private static java.util.Map<String, Integer> positive(java.util.Map<String, Integer> m) {
    java.util.Map<String, Integer> out = new java.util.TreeMap<>();
    m.forEach(
        (k, v) -> {
          if (v > 0) {
            out.put(k, v);
          }
        });
    return out;
  }
}
