package com.pmart.hr.application;

import com.pmart.hr.infrastructure.HrStore;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import com.pmart.hr.infrastructure.HrStore.ProvisioningRow;
import com.pmart.hr.infrastructure.KeycloakAdminClient;
import com.pmart.hr.infrastructure.MailProvisionerClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 발급 진행(ADR-0056 결정 7). 한 직원씩 행을 잠그고(SKIP LOCKED — 여러 대여도 한 대만) 단계를 진행한다:
 *
 * <ol>
 *   <li>REQUESTED → 사서함 만들기(멱등) + 메일 서버가 그 주소를 받는지 확인 → MAILBOX_CREATED(받기 전에는 안내 메일을 보내지 않는다)
 *   <li>MAILBOX_CREATED → Keycloak 사용자 만들기(이미 있으면 운영자 ID 가 같을 때만 그대로) → IAM_CREATED
 *   <li>IAM_CREATED → 비밀번호 설정 메일(재직 중일 때만) → INVITE_SENT
 *   <li>INVITE_SENT → EmployeeRegistered(그 사이 정지·퇴사됐으면 그 상태 사건도) → DONE
 * </ol>
 *
 * 실패는 단계를 그대로 두고 횟수·오류 코드·다음 시도 시각(지수 대기)을 남긴다. 횟수를 다 쓰거나 다시 해도 안 될 거절(IAM_USERNAME_TAKEN 등)이면
 * FAILED 로 두고 보상(만든 사서함 비활성, 만든 Keycloak 사용자 비활성)까지 되면 COMPENSATED. 인사팀이 provisioning-retries 로 다시
 * 시작한다.
 */
@Component
public class ProvisioningWorker implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);

  /** 다시 해도 같은 결과일 거절 — 바로 FAILED. */
  private static final List<String> PERMANENT =
      List.of(
          "IAM_USERNAME_TAKEN",
          "IAM_CLIENT_REJECTED",
          "MAILBOX_400",
          "KEYCLOAK_400",
          "KEYCLOAK_403");

  private final HrStore store;
  private final EmployeeEvents events;
  private final HrProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectProvider<KeycloakAdminClient> keycloak;
  private final ObjectProvider<MailProvisionerClient> mail;
  private final ExecutorService kicker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "hr-provisioning-kick");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean kickQueued = new AtomicBoolean();

  public ProvisioningWorker(
      HrStore store,
      EmployeeEvents events,
      HrProperties props,
      TransactionTemplate tx,
      Clock clock,
      ObjectProvider<KeycloakAdminClient> keycloak,
      ObjectProvider<MailProvisionerClient> mail) {
    this.store = store;
    this.events = events;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.keycloak = keycloak;
    this.mail = mail;
  }

  /** 등록 때 받은 요청 권한을 승인 요청으로(ADR-0056 5-4) — 등록 사건과 같은 트랜잭션. 재직 중일 때만(발급 중 정지·퇴사면 PENDING 그대로). */
  private void submitGrants(EmployeeRow e, Instant now) {
    if (!"ACTIVE".equals(e.status())) {
      return;
    }
    List<HrStore.GrantRow> pending =
        store.grants(e.operatorId()).stream()
            .filter(g -> "PENDING".equals(g.submission()))
            .toList();
    if (pending.isEmpty()) {
      return;
    }
    String requestId = "GR-" + java.util.UUID.randomUUID();
    events.grantRequested(e, requestId, pending, now);
    store.markGrantsSubmitted(e.operatorId(), requestId);
    log.info("요청 권한 제출: operatorId={} {}행", e.operatorId(), pending.size());
  }

  /** 등록·재시도 직후 바로 한 번 돌린다(다음 주기를 기다리지 않게). 이미 대기 중이면 합친다. */
  public void kick() {
    if (kickQueued.compareAndSet(false, true)) {
      kicker.execute(
          () -> {
            kickQueued.set(false);
            poll();
          });
    }
  }

  @Scheduled(
      initialDelayString = "${pmart.hr.provisioning.poll-interval:PT5S}",
      fixedDelayString = "${pmart.hr.provisioning.poll-interval:PT5S}")
  public void poll() {
    for (int i = 0; i < 20; i++) {
      Boolean worked = tx.execute(s -> processOne());
      if (!Boolean.TRUE.equals(worked)) {
        return;
      }
    }
  }

  /** 차례인 한 명을 잠그고 갈 수 있는 데까지 진행한다. 진행할 것이 없으면 false. 트랜잭션 안 — 예외를 밖으로 내지 않는다. */
  private boolean processOne() {
    Instant now = now();
    List<ProvisioningRow> due = store.lockDue(now, 1);
    if (due.isEmpty()) {
      return false;
    }
    ProvisioningRow p = due.getFirst();
    EmployeeRow e = store.employee(p.operatorId(), true).orElseThrow();
    String step = p.step();
    try {
      KeycloakAdminClient kc = keycloak.getIfAvailable();
      MailProvisionerClient mp = mail.getIfAvailable();
      if (kc == null || mp == null) {
        throw new MailProvisionerClient.Rejected("CONFIG_REQUIRED");
      }
      if ("REQUESTED".equals(step)) {
        mp.create(e.mailAddress());
        if (!mp.receiving(e.mailAddress())) {
          // 메일 서버가 새 계정을 반영하는 데 몇 초 걸린다 — 실패로 세되 곧 다시 본다
          throw new MailProvisionerClient.Unavailable("MAILBOX_NOT_READY", null);
        }
        step = advance(e, "MAILBOX_CREATED", now);
      }
      if ("MAILBOX_CREATED".equals(step)) {
        String id = kc.createUser(e.mailAddress(), e.mailAddress(), e.name(), e.operatorId());
        store.setKeycloakUserId(e.operatorId(), id, now);
        if (!"ACTIVE".equals(e.status())) {
          kc.setEnabled(id, false);
        }
        e = store.employee(e.operatorId(), false).orElseThrow();
        step = advance(e, "IAM_CREATED", now);
      }
      if ("IAM_CREATED".equals(step)) {
        if ("ACTIVE".equals(e.status()) && e.keycloakUserId() != null) {
          kc.sendPasswordSetupMail(e.keycloakUserId());
        }
        step = advance(e, "INVITE_SENT", now);
      }
      if ("INVITE_SENT".equals(step)) {
        events.registered(e, now);
        submitGrants(e, now);
        if (!"ACTIVE".equals(e.status())) {
          // 발급 중에 정지·퇴사됐다 — 등록 사건 뒤에 지금 상태를 알린다(버전 하나 올려서)
          store.bumpVersion(e.operatorId(), now);
          events.statusChanged(store.employee(e.operatorId(), false).orElseThrow(), now);
          store.markIamSync(e.operatorId(), now);
        }
        advance(e, "DONE", now);
        log.info("계정 발급 완료: operatorId={}", e.operatorId());
      }
    } catch (KeycloakAdminClient.Rejected ex) {
      fail(e, step, p, ex.code(), now);
    } catch (MailProvisionerClient.Rejected ex) {
      fail(e, step, p, ex.code(), now);
    } catch (KeycloakAdminClient.Unavailable ex) {
      fail(e, step, p, "KEYCLOAK_UNAVAILABLE", now);
    } catch (MailProvisionerClient.Unavailable ex) {
      fail(
          e,
          step,
          p,
          "MAILBOX_NOT_READY".equals(ex.getMessage()) ? "MAILBOX_NOT_READY" : "MAILBOX_UNAVAILABLE",
          now);
    } catch (RuntimeException ex) {
      log.warn(
          "계정 발급 예외: operatorId={} step={} {}",
          e.operatorId(),
          step,
          ex.getClass().getSimpleName());
      fail(e, step, p, "UNEXPECTED", now);
    }
    return true;
  }

  private String advance(EmployeeRow e, String step, Instant now) {
    store.advance(e.operatorId(), step, now);
    return step;
  }

  /** 실패 기록. 횟수를 다 썼거나 영구 거절이면 FAILED + 보상. */
  private void fail(EmployeeRow e, String step, ProvisioningRow p, String code, Instant now) {
    // 이번 실행에서 앞 단계가 진행됐으면(p 는 처음 단계) 새 단계 기준으로 처음부터 센다
    int attempts = (step.equals(p.step()) ? p.attempts() : 0) + 1;
    boolean give = PERMANENT.contains(code) || attempts >= props.provisioning().maxAttempts();
    if (!give) {
      store.failed(
          e.operatorId(),
          step,
          attempts,
          code,
          now.plus(props.provisioning().backoff(attempts)),
          now);
      log.info(
          "계정 발급 재시도 예정: operatorId={} step={} code={} attempts={}",
          e.operatorId(),
          step,
          code,
          attempts);
      return;
    }
    String result =
        compensate(store.employee(e.operatorId(), false).orElseThrow(), step)
            ? "COMPENSATED"
            : "FAILED";
    store.failed(e.operatorId(), result, attempts, code, null, now);
    log.warn("계정 발급 실패: operatorId={} step={} code={} → {}", e.operatorId(), step, code, result);
  }

  /** 이번 발급에서 만든 것을 비활성(지우지 않는다). 다 됐으면 true. */
  private boolean compensate(EmployeeRow e, String step) {
    try {
      KeycloakAdminClient kc = keycloak.getIfAvailable();
      MailProvisionerClient mp = mail.getIfAvailable();
      if (e.keycloakUserId() != null && kc != null) {
        kc.setEnabled(e.keycloakUserId(), false);
      }
      if (!"REQUESTED".equals(step) && mp != null) {
        mp.disable(e.mailAddress());
      } else if (mp != null) {
        try {
          mp.disable(e.mailAddress()); // 만들다 말았을 수 있다 — 없으면 404
        } catch (MailProvisionerClient.Rejected notFound) {
          // 없다 — 되돌릴 것 없음
        }
      }
      return kc != null && mp != null;
    } catch (RuntimeException ex) {
      log.warn("계정 발급 보상 실패: operatorId={} {}", e.operatorId(), ex.getClass().getSimpleName());
      return false;
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public void destroy() {
    kicker.shutdownNow();
  }

  /** 시험용: 지금 차례인 것을 모두 진행. */
  public void drain() {
    poll();
  }

  static @Nullable String nullIfBlank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
