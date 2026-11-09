package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.infrastructure.PlanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 계산 입력 총량 상한(0.12.0, 사용자 결정 2026-11-09 — 기간은 정하지 않고 LAN 2GB): 넘으면 오래된 입력부터 지운다(확정 안 된 계획 먼저). 설정
 * {@code pmart.capacity.plan.input-max-bytes} 가 없으면 아무것도 지우지 않는다.
 */
@Component
public class PlanInputSweeper {

  private static final Logger log = LoggerFactory.getLogger(PlanInputSweeper.class);

  private final PlanStore plans;
  private final PlanProperties props;

  public PlanInputSweeper(PlanStore plans, PlanProperties props) {
    this.plans = plans;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${pmart.capacity.plan.input-sweep-interval:PT1H}")
  public void sweep() {
    Long max = props.inputMaxBytes();
    if (max == null || max <= 0) {
      return;
    }
    long total = plans.totalInputBytes();
    if (total <= max) {
      return;
    }
    long freed = plans.deleteOldestInputs(total - max);
    if (freed > 0) {
      log.info("배정 추천 입력 {} 바이트 지움(총량 {} > 상한 {})", freed, total, max);
    }
  }
}
