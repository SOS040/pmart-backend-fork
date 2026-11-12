package com.pmart.hr.application;

import com.pmart.hr.infrastructure.HrStore;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 계약 종료일 자동 정지(ADR-0056 결정 5, 설계 §8-1). 계약 종료일(Asia/Seoul 날짜)이 지난 재직 중 직원을 정지(CONTRACT_ENDED)한다. 한
 * 시간마다 본다 — 자정에 서비스가 꺼져 있어도 다음 실행에서 처리된다. 종료일 당일은 근무일이라 다음 날 정지.
 */
@Component
public class ContractEndJob {

  private static final Logger log = LoggerFactory.getLogger(ContractEndJob.class);

  private final HrStore store;
  private final EmployeeService employees;
  private final HrProperties props;
  private final Clock clock;

  public ContractEndJob(HrStore store, EmployeeService employees, HrProperties props, Clock clock) {
    this.store = store;
    this.employees = employees;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
  public void run() {
    LocalDate today = LocalDate.now(clock.withZone(props.zone()));
    for (String operatorId : store.contractEnded(today)) {
      try {
        employees.contractEnded(operatorId);
        log.info("계약 종료 자동 정지: operatorId={}", operatorId);
      } catch (RuntimeException e) {
        log.warn(
            "계약 종료 자동 정지 실패(다음 실행에 다시): operatorId={} {}",
            operatorId,
            e.getClass().getSimpleName());
      }
    }
  }
}
