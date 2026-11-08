package com.pmart.mfc.inbound.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자동 선반 보충 점검 주기(IV-04 "등록 점검 주기"). {@code pmart.inbound.replenishment-enabled} 가 켜진 환경만. 실패는 다음
 * 주기에 다시 — 점검은 멱등(진행 중 보충을 빼고 계산).
 */
@Component
public class ReplenishmentScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReplenishmentScheduler.class);

  private final MoveOrderService moves;
  private final InboundProperties props;

  public ReplenishmentScheduler(MoveOrderService moves, InboundProperties props) {
    this.moves = moves;
    this.props = props;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inbound.replenishment-interval:PT1M}",
      initialDelayString = "${pmart.inbound.replenishment-interval:PT1M}")
  void check() {
    if (!props.replenishmentEnabled()) {
      return;
    }
    try {
      moves.recordReplenishments();
    } catch (RuntimeException e) {
      log.warn("자동 보충 점검 실패(다음 주기에 다시): {}", e.getMessage());
    }
  }
}
