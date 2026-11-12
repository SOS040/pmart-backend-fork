package com.pmart.hr.application;

import com.pmart.hr.infrastructure.HrStore;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import com.pmart.hr.infrastructure.KeycloakAdminClient;
import com.pmart.hr.infrastructure.MailProvisionerClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재직 상태를 Keycloak·사서함에 맞춘다(ADR-0056 결정 8) — 정지·퇴사: Keycloak 비활성 + 세션 로그아웃, 퇴사: 사서함 비활성(지우지 않음 —
 * D7). 복귀: Keycloak·사서함 다시 활성. 실패하면 지수 대기로 다시 한다. 즉시 차단은 사건을 받은 employee-auth-service 몫이라 여기가 늦어도
 * 이미 나간 PMart 토큰은 막힌다. 계정 발급이 끝나지 않은 직원은 발급 완료 때 다시 표시된다.
 */
@Component
public class IamSyncWorker {

  private static final Logger log = LoggerFactory.getLogger(IamSyncWorker.class);

  private final HrStore store;
  private final HrProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectProvider<KeycloakAdminClient> keycloak;
  private final ObjectProvider<MailProvisionerClient> mail;

  public IamSyncWorker(
      HrStore store,
      HrProperties props,
      TransactionTemplate tx,
      Clock clock,
      ObjectProvider<KeycloakAdminClient> keycloak,
      ObjectProvider<MailProvisionerClient> mail) {
    this.store = store;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.keycloak = keycloak;
    this.mail = mail;
  }

  @Scheduled(
      initialDelayString = "${pmart.hr.provisioning.poll-interval:PT5S}",
      fixedDelayString = "${pmart.hr.provisioning.poll-interval:PT5S}")
  public void poll() {
    for (int i = 0; i < 20; i++) {
      Boolean worked = tx.execute(s -> syncOne());
      if (!Boolean.TRUE.equals(worked)) {
        return;
      }
    }
  }

  private boolean syncOne() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    List<EmployeeRow> due = store.lockIamSyncDue(now, 1);
    if (due.isEmpty()) {
      return false;
    }
    EmployeeRow e = due.getFirst();
    boolean registered =
        store.provisioning(e.operatorId(), false).map(p -> "DONE".equals(p.step())).orElse(false);
    if (!registered) {
      store.iamSyncDone(e.operatorId(), now); // 발급 완료 때 다시 표시된다
      return true;
    }
    try {
      KeycloakAdminClient kc = keycloak.getIfAvailable();
      MailProvisionerClient mp = mail.getIfAvailable();
      if (kc == null || mp == null) {
        throw new IllegalStateException("CONFIG_REQUIRED");
      }
      boolean active = "ACTIVE".equals(e.status());
      if (e.keycloakUserId() != null) {
        kc.setEnabled(e.keycloakUserId(), active);
        if (!active) {
          kc.logoutSessions(e.keycloakUserId());
        }
      }
      if ("LEFT".equals(e.status())) {
        mp.disable(e.mailAddress());
      } else if (active) {
        mp.enable(e.mailAddress());
      }
      store.iamSyncDone(e.operatorId(), now);
      log.info("Keycloak·사서함 상태 맞춤: operatorId={} status={}", e.operatorId(), e.status());
    } catch (RuntimeException ex) {
      String code =
          ex instanceof KeycloakAdminClient.Rejected r
              ? r.code()
              : ex instanceof MailProvisionerClient.Rejected m
                  ? m.code()
                  : ex instanceof KeycloakAdminClient.Unavailable
                      ? "KEYCLOAK_UNAVAILABLE"
                      : ex instanceof MailProvisionerClient.Unavailable
                          ? "MAILBOX_UNAVAILABLE"
                          : "UNEXPECTED";
      store.iamSyncFailed(
          e.operatorId(), code, now.plus(props.provisioning().backoff(e.iamSyncAttempts() + 1)));
      log.warn("Keycloak·사서함 상태 맞추기 실패(다시 시도): operatorId={} code={}", e.operatorId(), code);
    }
    return true;
  }
}
