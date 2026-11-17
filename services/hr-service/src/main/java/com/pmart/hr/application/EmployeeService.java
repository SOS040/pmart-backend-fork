package com.pmart.hr.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.hr.infrastructure.HrStore;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import com.pmart.hr.infrastructure.HrStore.GrantRow;
import com.pmart.hr.infrastructure.HrStore.ProvisioningRow;
import com.pmart.hr.infrastructure.KeycloakAdminClient;
import com.pmart.hr.infrastructure.MailProvisionerClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 직원 등록·조회·소속 변경·정지·복귀·퇴사(ADR-0056 결정 5·8·12). 모든 명령은 HR_ADMIN 만(D3), 본인 조회만 모든 직원.
 *
 * <ul>
 *   <li>등록: 운영자 ID(OP-UUID)·사번(P연도-5자리)을 매기고 계정 발급 진행을 REQUESTED 로 둔다 — 실제 발급은 {@link
 *       ProvisioningWorker}. 요청 권한은 저장만(approval 제출은 연동 뒤).
 *   <li>정지·복귀·퇴사: 인사 상태와 사건(EmployeeSuspended·Reinstated·Left)을 같은 트랜잭션에 쓰고, Keycloak·사서함 반영은
 *       {@link IamSyncWorker} 가 재시도하며 맞춘다(즉시 차단은 사건을 받은 employee-auth-service 가 한다). 계정 발급이 끝나기 전이면
 *       사건은 발급 완료 때 상태와 함께 낸다.
 *   <li>같은 메일 ID 는 퇴사자 것이라도 다시 쓰지 않는다.
 * </ul>
 */
@Service
public class EmployeeService {

  public static final String HR_ADMIN = "HR_ADMIN";
  private static final Pattern MAIL_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

  /** 응답용 묶음. */
  public record EmployeeView(
      EmployeeRow employee, ProvisioningRow provisioning, List<GrantRow> grants) {}

  public record Registration(
      String name,
      String mailId,
      String employmentType,
      String affiliationType,
      String affiliationCode,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      List<GrantRequest> grants) {}

  public record GrantRequest(
      String role,
      List<String> centers,
      @Nullable LocalDate validFrom,
      @Nullable LocalDate validTo,
      @Nullable String reason) {}

  private final HrStore store;
  private final EmployeeEvents events;
  private final RequestIdempotency idempotency;
  private final HrProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectProvider<KeycloakAdminClient> keycloak;
  private final ObjectProvider<MailProvisionerClient> mail;
  private final ProvisioningWorker worker;

  public EmployeeService(
      HrStore store,
      EmployeeEvents events,
      RequestIdempotency idempotency,
      HrProperties props,
      TransactionTemplate tx,
      Clock clock,
      ObjectProvider<KeycloakAdminClient> keycloak,
      ObjectProvider<MailProvisionerClient> mail,
      ProvisioningWorker worker) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.keycloak = keycloak;
    this.mail = mail;
    this.worker = worker;
  }

  // ------------------------------------------------------------------ 등록

  public EmployeeView register(String idempotencyKey, String sourceSystem, Registration r) {
    PmartPrincipal hr = requireHrAdmin();
    validate(r);
    String operatorId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "registerEmployee", hr.subject()),
                r,
                String.class,
                () -> doRegister(r, hr.subject(), sourceSystem))
            .value();
    worker.kick();
    return view(operatorId);
  }

  private String doRegister(Registration r, String actor, String sourceSystem) {
    if (store.mailIdTaken(r.mailId())) {
      throw new PmartException(
          "MAIL_ID_TAKEN",
          HttpStatus.CONFLICT,
          "이미 쓰였거나 예전에 쓰인 메일 ID 입니다(퇴사자 ID 도 다시 쓰지 않습니다).",
          false,
          List.of());
    }
    Instant now = now();
    int year = LocalDate.now(clock.withZone(props.zone())).getYear();
    String employeeNo = "P%d-%05d".formatted(year, store.nextEmployeeNo(year));
    String operatorId = "OP-" + UUID.randomUUID();
    String address = r.mailId() + "@" + props.mailDomain();
    store.insertEmployee(
        operatorId,
        employeeNo,
        r.name(),
        r.mailId(),
        address,
        r.employmentType(),
        r.affiliationType(),
        r.affiliationCode(),
        r.startDate(),
        r.endDate(),
        actor,
        now);
    store.insertProvisioning(operatorId, now);
    if (HrStore.HQ_DEPARTMENT.equals(r.affiliationType())) {
      store.ensureDepartment(r.affiliationCode(), actor, now); // 부서 코드 목록에 없으면 더한다(거절하지 않음)
    }
    for (GrantRequest g : r.grants()) {
      store.insertGrant(
          operatorId,
          g.role(),
          g.centers().stream().distinct().sorted().toList(),
          g.validFrom(),
          g.validTo(),
          g.reason(),
          now);
    }
    store.insertChange(operatorId, "REGISTERED", actor, sourceSystem, null, null, now);
    log.info("직원 등록: operatorId={} employeeNo={}", operatorId, employeeNo);
    return operatorId;
  }

  /**
   * 이름에 쓸 수 없는 문자 — Keycloak 사용자 프로필 firstName 검사(person-name-prohibited-characters)와 같은 목록. 이 문자가
   * 있으면 계정 발급 2단계(Keycloak 사용자 만들기)가 400 으로 실패하고 사서함까지 되돌려졌다(2026-11-16 PC 2 "[시험] …" —
   * KEYCLOAK_400). 등록 때 미리 막는다(2026-11-17).
   */
  static final java.util.regex.Pattern NAME_PROHIBITED =
      java.util.regex.Pattern.compile("[<>&\"$%!#?§;*~/\\\\|^=\\[\\]{}()\\p{Cntrl}]");

  private void validate(Registration r) {
    if (r.name() == null || r.name().isBlank() || NAME_PROHIBITED.matcher(r.name()).find()) {
      throw invalid(
          "name",
          "이름에 쓸 수 없는 문자가 있습니다: < > & \" $ % ! # ? § ; * ~ / \\ | ^ = [ ] { } ( ) (로그인 계정 규칙).");
    }
    if (!MAIL_ID.matcher(r.mailId()).matches()
        || r.mailId().contains("..")
        || props.reservedMailIds().contains(r.mailId())) {
      throw invalid("mailId", "쓸 수 없는 메일 ID 입니다(소문자·숫자·._- 3~32자, 예약어 불가).");
    }
    boolean contract = !"REGULAR".equals(r.employmentType());
    if (contract && r.endDate() == null) {
      throw invalid("endDate", "파트타임·일일크루는 계약 종료일이 필요합니다.");
    }
    if (!contract && r.endDate() != null) {
      throw invalid("endDate", "정규직은 계약 종료일을 두지 않습니다.");
    }
    if (r.endDate() != null && r.endDate().isBefore(r.startDate())) {
      throw invalid("endDate", "계약 종료일이 근무 시작일보다 앞입니다.");
    }
    for (GrantRequest g : r.grants()) {
      if (g.validFrom() != null && g.validTo() != null && g.validTo().isBefore(g.validFrom())) {
        throw invalid("requestedGrants", "권한 기간이 거꾸로입니다: " + g.role());
      }
    }
  }

  // ------------------------------------------------------------------ 조회

  public EmployeeView get(String operatorId) {
    requireHrAdmin();
    return view(operatorId);
  }

  public List<EmployeeView> list(
      @Nullable String status,
      @Nullable String affiliationCode,
      @Nullable String q,
      @Nullable String cursor,
      int size) {
    requireHrAdmin();
    return store.list(status, affiliationCode, q, cursor, Math.min(Math.max(size, 1), 200)).stream()
        .map(e -> view(e.operatorId()))
        .toList();
  }

  /** 본인(토큰의 운영자 ID). */
  public EmployeeRow me() {
    PmartPrincipal p = requireOperator();
    return store
        .employee(p.subject(), false)
        .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "인사 기록이 없습니다."));
  }

  private static final Pattern NAME_LOOKUP_ID = Pattern.compile("^[A-Za-z0-9_.:@-]{1,128}$");
  static final int NAME_LOOKUP_MAX = 200;

  /**
   * 내부 이름 조회(0.5.0, 2026-11-17 — employee-auth-service 가 승인자·회수자 이름을 붙인다). 서비스 간 경로라 역할 검사가
   * 없다(게이트웨이가 {@code /internal/**} 를 막는다). 퇴사자 포함, 모르는 ID 는 빠진다. 형식이 틀린 ID·200개 초과는 400.
   */
  public java.util.Map<String, String> names(List<String> operatorIds) {
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    for (String id : operatorIds) {
      if (id == null || !NAME_LOOKUP_ID.matcher(id).matches()) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "운영자 ID 형식이 아닙니다.");
      }
      ids.add(id);
    }
    if (ids.isEmpty() || ids.size() > NAME_LOOKUP_MAX) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "운영자 ID 는 1~200개입니다.");
    }
    return store.names(ids);
  }

  private EmployeeView view(String operatorId) {
    EmployeeRow e =
        store
            .employee(operatorId, false)
            .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다."));
    ProvisioningRow p = store.provisioning(operatorId, false).orElseThrow();
    return new EmployeeView(e, p, store.grants(operatorId));
  }

  // ------------------------------------------------------------------ 소속·재직 상태

  public EmployeeView changeAffiliation(
      String idempotencyKey,
      String sourceSystem,
      String operatorId,
      @Nullable Long expectedVersion,
      String type,
      String code,
      String reason) {
    PmartPrincipal hr = requireHrAdmin();
    requireNotSelf(hr, operatorId, "소속 변경");
    idempotency.execute(
        new RequestKey(
            sourceSystem, idempotencyKey, "changeAffiliation:" + operatorId, hr.subject()),
        List.of(type, code, reason),
        String.class,
        () -> {
          EmployeeRow e = locked(operatorId, expectedVersion);
          Instant now = now();
          store.updateAffiliation(operatorId, type, code, now);
          if (HrStore.HQ_DEPARTMENT.equals(type)) {
            store.ensureDepartment(code, hr.subject(), now);
          }
          store.insertChange(
              operatorId,
              "AFFILIATION_CHANGED",
              hr.subject(),
              sourceSystem,
              reason,
              "{\"from\":\"%s\",\"to\":\"%s\"}".formatted(e.affiliationCode(), code),
              now);
          // 이전 센터 권한 자동 종료(2026-11-16 BO-01 — 실서비스 추천안): 바뀐 날 다음 날 00:00(그날 안에 인계)
          boolean changed = !e.affiliationType().equals(type) || !e.affiliationCode().equals(code);
          boolean registered =
              store.provisioning(operatorId, false).map(p -> "DONE".equals(p.step())).orElse(false);
          if (changed && registered) {
            Instant until =
                HrStore.CENTER.equals(e.affiliationType())
                    ? today().plusDays(1).atStartOfDay(props.zone()).toInstant()
                    : null;
            events.affiliationChanged(
                store.employee(operatorId, false).orElseThrow(),
                e.affiliationType(),
                e.affiliationCode(),
                now,
                until);
          }
          return operatorId;
        });
    return view(operatorId);
  }

  public EmployeeView suspend(
      String idempotencyKey,
      String sourceSystem,
      String operatorId,
      @Nullable Long expectedVersion,
      String reason) {
    PmartPrincipal hr = requireHrAdmin();
    requireNotSelf(hr, operatorId, "정지");
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "suspend:" + operatorId, hr.subject()),
        reason,
        String.class,
        () -> {
          EmployeeRow e = locked(operatorId, expectedVersion);
          if ("LEFT".equals(e.status())) {
            throw PmartException.invalidState("퇴사한 직원은 정지할 수 없습니다.");
          }
          if (!"SUSPENDED".equals(e.status())) {
            changeStatus(
                e, "SUSPENDED", "SUSPENDED", null, null, false, hr.subject(), sourceSystem, reason);
          }
          return operatorId;
        });
    return view(operatorId);
  }

  /**
   * 재직 중 권한 추가·변경 요청(BO-01, 2026-11-15 사용자 결정 — 추가·변경은 승인, 회수는 employee-auth-service 에서 즉시). 계정 발급이
   * 끝난 재직 중 직원만, 자기 권한은 못 올린다, 결정 안 된 권한 요청이 있으면 409(approval 이 같은 대상에 열린 승인을 하나만 둔다). 요청 행을 넣고 같은
   * 트랜잭션에서 GrantRequested(요청자 = 이 인사팀 직원) — 등록 때와 같은 길(ApprovalDecided → hr 표시·employee-auth 적용).
   */
  /**
   * 자기 인사 상태는 다른 인사 담당자가 바꾼다(권한 빈틈 정리 2026-11-17 — 권한 요청과 같은 원칙). 자기 정지·퇴사로 계정을 잠그거나, 자기 소속을 바꿔 센터
   * 권한을 움직이는 것을 막는다.
   */
  private static void requireNotSelf(PmartPrincipal hr, String operatorId, String what) {
    if (operatorId.equals(hr.subject())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "자기 자신은 " + what + "할 수 없습니다(다른 인사 담당자가 처리).");
    }
  }

  public EmployeeView requestGrants(
      String idempotencyKey,
      String sourceSystem,
      String operatorId,
      @Nullable Long expectedVersion,
      List<GrantRequest> grants,
      String reason) {
    PmartPrincipal hr = requireHrAdmin();
    if (operatorId.equals(hr.subject())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "자기 권한은 요청할 수 없습니다(다른 인사 담당자가 요청).");
    }
    for (GrantRequest g : grants) {
      if (g.validFrom() != null && g.validTo() != null && g.validTo().isBefore(g.validFrom())) {
        throw invalid("grants", "권한 기간이 거꾸로입니다: " + g.role());
      }
      if (g.validTo() != null && g.validTo().isBefore(today())) {
        throw invalid("grants", "이미 지난 종료일입니다: " + g.role());
      }
    }
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "grant-requests:" + operatorId, hr.subject()),
        java.util.Arrays.asList(grants, reason),
        String.class,
        () -> {
          EmployeeRow e = locked(operatorId, expectedVersion);
          if (!"ACTIVE".equals(e.status())) {
            throw PmartException.invalidState("재직 중인 직원만 권한을 요청할 수 있습니다(지금 " + e.status() + ").");
          }
          boolean provisioned =
              store.provisioning(operatorId, false).map(p -> "DONE".equals(p.step())).orElse(false);
          if (!provisioned) {
            throw PmartException.invalidState(
                "계정 발급이 끝난 뒤에 요청할 수 있습니다(등록 때 고른 권한은 발급이 끝나면 자동으로 올라갑니다).");
          }
          boolean open =
              store.grants(operatorId).stream()
                  .anyMatch(
                      g -> "PENDING".equals(g.submission()) || "SUBMITTED".equals(g.submission()));
          if (open) {
            throw new PmartException(
                "GRANT_REQUEST_PENDING",
                HttpStatus.CONFLICT,
                "아직 결정되지 않은 권한 요청이 있습니다 — 승인함에서 결정된 뒤에 다시 요청하세요.",
                false,
                List.of());
          }
          Instant now = now();
          for (GrantRequest g : grants) {
            store.insertGrant(
                operatorId,
                g.role(),
                g.centers().stream().distinct().sorted().toList(),
                g.validFrom(),
                g.validTo(),
                g.reason() == null || g.reason().isBlank() ? reason : g.reason(),
                now);
          }
          List<GrantRow> pending =
              store.grants(operatorId).stream()
                  .filter(g -> "PENDING".equals(g.submission()))
                  .toList();
          String requestId = "GR-" + UUID.randomUUID();
          events.grantRequested(e, requestId, pending, now, hr.subject());
          store.markGrantsSubmitted(operatorId, requestId);
          store.insertChange(
              operatorId, "GRANT_REQUESTED", hr.subject(), sourceSystem, reason, null, now);
          log.info("권한 요청 제출: operatorId={} {}행 by {}", operatorId, pending.size(), hr.subject());
          return operatorId;
        });
    return view(operatorId);
  }

  public EmployeeView reinstate(
      String idempotencyKey,
      String sourceSystem,
      String operatorId,
      @Nullable Long expectedVersion,
      String reason,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate) {
    PmartPrincipal hr = requireHrAdmin();
    requireNotSelf(hr, operatorId, "정지 해제");
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "reinstate:" + operatorId, hr.subject()),
        java.util.Arrays.asList(reason, startDate, endDate),
        String.class,
        () -> {
          EmployeeRow e = locked(operatorId, expectedVersion);
          if ("ACTIVE".equals(e.status())) {
            return operatorId;
          }
          boolean contract = !"REGULAR".equals(e.employmentType());
          LocalDate newEnd = endDate != null ? endDate : e.endDate();
          if (contract && (newEnd == null || newEnd.isBefore(today()))) {
            throw invalid("endDate", "파트타임·일일크루는 앞으로의 계약 종료일이 필요합니다.");
          }
          changeStatus(
              e,
              "ACTIVE",
              null,
              startDate,
              contract ? newEnd : null,
              true,
              hr.subject(),
              sourceSystem,
              reason);
          return operatorId;
        });
    return view(operatorId);
  }

  public EmployeeView depart(
      String idempotencyKey,
      String sourceSystem,
      String operatorId,
      @Nullable Long expectedVersion,
      String reason) {
    PmartPrincipal hr = requireHrAdmin();
    requireNotSelf(hr, operatorId, "퇴사 처리");
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "depart:" + operatorId, hr.subject()),
        reason,
        String.class,
        () -> {
          EmployeeRow e = locked(operatorId, expectedVersion);
          if (!"LEFT".equals(e.status())) {
            changeStatus(e, "LEFT", "LEFT", null, null, false, hr.subject(), sourceSystem, reason);
          }
          return operatorId;
        });
    return view(operatorId);
  }

  /** 계약 종료 자동 정지({@link ContractEndJob}). */
  public void contractEnded(String operatorId) {
    tx.executeWithoutResult(
        s -> {
          EmployeeRow e = store.employee(operatorId, true).orElseThrow();
          if ("ACTIVE".equals(e.status()) && e.endDate() != null && e.endDate().isBefore(today())) {
            changeStatus(
                e,
                "SUSPENDED",
                "CONTRACT_ENDED",
                null,
                null,
                false,
                "system",
                "hr-service",
                "계약 종료일 경과");
          }
        });
  }

  /** 인사 상태 변경 + 변경 기록 + 사건(계정 발급이 끝난 직원만 — 아니면 발급 완료 때 상태와 함께 낸다). 트랜잭션 안. */
  private void changeStatus(
      EmployeeRow e,
      String status,
      @Nullable String reason,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      boolean changeDates,
      String actor,
      String sourceSystem,
      String why) {
    Instant now = now();
    store.updateStatus(e.operatorId(), status, reason, startDate, endDate, changeDates, now);
    String type =
        switch (status) {
          case "ACTIVE" -> "REINSTATED";
          case "LEFT" -> "LEFT";
          default -> Objects.equals(reason, "CONTRACT_ENDED") ? "CONTRACT_ENDED" : "SUSPENDED";
        };
    store.insertChange(e.operatorId(), type, actor, sourceSystem, why, null, now);
    boolean registered =
        store.provisioning(e.operatorId(), false).map(p -> "DONE".equals(p.step())).orElse(false);
    if (registered) {
      EmployeeRow after = store.employee(e.operatorId(), false).orElseThrow();
      events.statusChanged(after, now);
    }
    log.info("직원 상태: operatorId={} {} → {}", e.operatorId(), e.status(), status);
  }

  // ------------------------------------------------------------------ 계정 발급 보조

  public void resendInvite(String operatorId) {
    PmartPrincipal hr = requireHrAdmin();
    EmployeeRow e = view(operatorId).employee();
    if (e.keycloakUserId() == null || !"ACTIVE".equals(e.status())) {
      throw PmartException.invalidState("Keycloak 계정이 아직 없거나 재직 중이 아닙니다.");
    }
    try {
      keycloak().sendPasswordSetupMail(e.keycloakUserId());
    } catch (KeycloakAdminClient.Unavailable e2) {
      throw unavailable("Keycloak");
    } catch (KeycloakAdminClient.Rejected e2) {
      throw new PmartException(
          e2.code(), HttpStatus.CONFLICT, "Keycloak 이 거절했습니다.", false, List.of());
    }
    tx.executeWithoutResult(
        s ->
            store.insertChange(
                operatorId, "INVITE_RESENT", hr.subject(), "hr-service", null, null, now()));
  }

  public EmployeeView retryProvisioning(String operatorId) {
    PmartPrincipal hr = requireHrAdmin();
    tx.executeWithoutResult(
        s -> {
          ProvisioningRow p =
              store
                  .provisioning(operatorId, true)
                  .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다."));
          if (!"FAILED".equals(p.step()) && !"COMPENSATED".equals(p.step())) {
            throw PmartException.invalidState("실패한 계정 발급만 다시 시도합니다(지금 " + p.step() + ").");
          }
          store.advance(operatorId, "REQUESTED", now());
          store.insertChange(
              operatorId, "PROVISIONING_RETRIED", hr.subject(), "hr-service", null, null, now());
        });
    worker.kick();
    return view(operatorId);
  }

  /** 웹메일 초기 비밀번호 — 응답에만, 저장·로그하지 않는다. */
  public String resetMailboxPassword(String operatorId) {
    PmartPrincipal hr = requireHrAdmin();
    EmployeeRow e = view(operatorId).employee();
    ProvisioningRow p = store.provisioning(operatorId, false).orElseThrow();
    if ("LEFT".equals(e.status())
        || "REQUESTED".equals(p.step())
        || "COMPENSATED".equals(p.step())) {
      throw PmartException.invalidState("사서함이 아직 없거나 퇴사한 직원입니다.");
    }
    byte[] raw = new byte[15];
    RANDOM.nextBytes(raw);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(raw); // 20자
    try {
      mail().setPassword(e.mailAddress(), password);
    } catch (MailProvisionerClient.Unavailable e2) {
      throw unavailable("mail-provisioner");
    } catch (MailProvisionerClient.Rejected e2) {
      throw new PmartException(
          e2.code(), HttpStatus.CONFLICT, "사서함 비밀번호를 바꾸지 못했습니다.", false, List.of());
    }
    tx.executeWithoutResult(
        s ->
            store.insertChange(
                operatorId,
                "MAILBOX_PASSWORD_RESET",
                hr.subject(),
                "hr-service",
                null,
                null,
                now()));
    return password;
  }

  // ------------------------------------------------------------------ 공통

  private EmployeeRow locked(String operatorId, @Nullable Long expectedVersion) {
    EmployeeRow e =
        store
            .employee(operatorId, true)
            .orElseThrow(() -> PmartException.notFound("EMPLOYEE_NOT_FOUND", "직원이 없습니다."));
    if (expectedVersion != null && expectedVersion != e.version()) {
      throw PmartException.versionConflict("다른 변경이 먼저 반영되었습니다(현재 " + e.version() + ").");
    }
    return e;
  }

  static PmartPrincipal requireHrAdmin() {
    PmartPrincipal p = requireOperator();
    if (!p.hasRole(HR_ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, HR_ADMIN + " 역할이 필요합니다.");
    }
    return p;
  }

  static PmartPrincipal requireOperator() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원 토큰이 필요합니다.");
    }
    return p;
  }

  private KeycloakAdminClient keycloak() {
    KeycloakAdminClient k = keycloak.getIfAvailable();
    if (k == null) {
      throw PmartException.configRequired("pmart.hr.keycloak");
    }
    return k;
  }

  private MailProvisionerClient mail() {
    MailProvisionerClient m = mail.getIfAvailable();
    if (m == null) {
      throw PmartException.configRequired("pmart.hr.mail-provisioner");
    }
    return m;
  }

  private static PmartException invalid(String field, String message) {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED, message, List.of(new PmartException.Detail(field, message)));
  }

  private static PmartException unavailable(String what) {
    return new PmartException(
        ErrorCode.DEPENDENCY_UNAVAILABLE, what + " 에 닿지 않습니다. 잠시 뒤 다시 시도하세요.");
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(props.zone()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
