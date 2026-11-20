package com.pmart.customer.application;

import com.pmart.customer.infrastructure.WithdrawalStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고객 탈퇴 주기 작업(2026-11-20 ADR-0077).
 *
 * <ul>
 *   <li>진행 중 탈퇴(PENDING·BLOCKED_BY_OBLIGATIONS·UNKNOWN)를 {@code check-interval}(기본 10분)마다 다시 확인 —
 *       의무가 끝나면 완료.
 *   <li>풀린 확인 번호를 {@code identity.rejoin-block}(P30D) 뒤 지운다(처리방침 "탈퇴 후 30일"). 설정이 없으면 지우지 않는다(기본값으로
 *       대신하지 않음).
 * </ul>
 *
 * 인스턴스가 여럿이어도 확인은 행 잠금 안에서 상태를 다시 보므로 겹쳐도 한 번만 완료된다.
 */
@Component
class WithdrawalJobs {

  private static final Logger log = LoggerFactory.getLogger(WithdrawalJobs.class);
  private static final int BATCH = 50;

  private final WithdrawalService withdrawals;
  private final WithdrawalStore store;
  private final WithdrawalProperties props;
  private final IdentityProperties identity;
  private final Clock clock;

  WithdrawalJobs(
      WithdrawalService withdrawals,
      WithdrawalStore store,
      WithdrawalProperties props,
      IdentityProperties identity,
      Clock clock) {
    this.withdrawals = withdrawals;
    this.store = store;
    this.props = props;
    this.identity = identity;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.customer.withdrawal.job-tick:PT1M}",
      fixedDelayString = "${pmart.customer.withdrawal.job-tick:PT1M}")
  void recheck() {
    Instant before = clock.instant().minus(props.interval());
    for (String id : store.dueForCheck(before, BATCH)) {
      try {
        withdrawals.check(id);
      } catch (RuntimeException e) {
        log.warn("탈퇴 확인 실패 {}: {}", id, e.getClass().getSimpleName());
      }
    }
  }

  @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT6H")
  void purgeReleasedIdentities() {
    if (!identity.complete()) {
      return;
    }
    int n = store.purgeReleasedIdentities(clock.instant().minus(identity.rejoinBlock()));
    if (n > 0) {
      log.info("탈퇴 후 보관 기간이 지난 확인 번호 {}건 지움", n);
    }
  }
}
