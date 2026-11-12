package com.pmart.employeeauth.application;

import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 뒤 하루 지난 로그인 시도·티켓을 지운다(한 시간마다). 세션·변경 기록은 지우지 않는다. */
@Component
class LoginRecordCleanup {

  private static final Logger log = LoggerFactory.getLogger(LoginRecordCleanup.class);

  private final EmployeeAuthStore store;
  private final Clock clock;

  LoginRecordCleanup(EmployeeAuthStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
  void purge() {
    int n = store.purgeExpired(clock.instant().minus(Duration.ofDays(1)));
    if (n > 0) {
      log.info("만료된 로그인 시도·티켓 {}건 정리", n);
    }
  }
}
