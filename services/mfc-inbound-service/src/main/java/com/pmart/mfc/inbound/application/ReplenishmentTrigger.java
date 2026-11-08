package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재고 변동 때 자동 보충을 바로 점검(2026-11-08 남은 일 15 — 1분 주기만 있던 것). mfc.inventory.v1 의 피킹·적치 등 실행 반영, 예약
 * 해제·만료, 실사 조정이 오면 "점검 필요" 만 표시하고, 짧은 주기({@code pmart.inbound.replenishment-trigger-delay}, 기본 5초)에
 * 한 번으로 모아 점검한다 — 사건마다 점검하면 피킹이 몰릴 때 같은 계산을 수십 번 한다. 점검은 멱등이라 1분 주기와 겹쳐도 된다. 실패하면 표시를 되돌려 다음에 다시.
 */
@Component
public class ReplenishmentTrigger {

  static final Set<String> TRIGGERS =
      Set.of(
          "InventoryExecutionApplied",
          "InventoryReleased",
          "InventoryHoldExpired",
          "StockAdjusted");
  private static final Logger log = LoggerFactory.getLogger(ReplenishmentTrigger.class);

  private final MoveOrderService moves;
  private final InboundProperties props;
  private final AtomicBoolean due = new AtomicBoolean();

  public ReplenishmentTrigger(MoveOrderService moves, InboundProperties props) {
    this.moves = moves;
    this.props = props;
  }

  /** 사건 소비 — 표시만(빠르게, 트랜잭션 안에서 외부 호출 안 함). */
  public void onInventoryEvent(EventEnvelope e) {
    if (props.replenishmentEnabled() && TRIGGERS.contains(e.eventType())) {
      due.set(true);
    }
  }

  @Scheduled(
      fixedDelayString = "${pmart.inbound.replenishment-trigger-delay:PT5S}",
      initialDelayString = "${pmart.inbound.replenishment-trigger-delay:PT5S}")
  void runIfDue() {
    if (!due.getAndSet(false)) {
      return;
    }
    try {
      moves.recordReplenishments();
    } catch (RuntimeException ex) {
      due.set(true);
      log.warn("재고 변동 보충 점검 실패(다음에 다시): {}", ex.getMessage());
    }
  }

  boolean due() {
    return due.get();
  }
}
