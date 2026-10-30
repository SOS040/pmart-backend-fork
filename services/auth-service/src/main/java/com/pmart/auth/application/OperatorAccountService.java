package com.pmart.auth.application;

import com.pmart.auth.domain.OperatorRules;
import com.pmart.auth.infrastructure.OperatorStore;
import com.pmart.auth.infrastructure.OperatorStore.AccountRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 계정 관리(OPERATOR_ADMIN 역할, ADR-0018). 권한 부여 승인 흐름(BO-01, approval)은 임시 백오피스 단계에서 쓰지 않고 관리자가 직접
 * 바꾼다 — 역할·센터 변경은 다음 로그인·토큰 갱신부터 반영된다. 비활성화·비밀번호 초기화는 열린 세션을 폐기한다.
 */
@Service
public class OperatorAccountService {

  private static final Logger log = LoggerFactory.getLogger(OperatorAccountService.class);

  public record NewAccount(
      String loginId,
      String displayName,
      String initialPassword,
      List<String> roles,
      List<String> centers) {}

  public record Change(
      String displayName, List<String> roles, List<String> centers, String state) {}

  private final OperatorStore store;
  private final PasswordEncoder encoder;
  private final RequestIdempotency idempotency;
  private final OperatorProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OperatorAccountService(
      OperatorStore store,
      PasswordEncoder encoder,
      RequestIdempotency idempotency,
      OperatorProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.encoder = encoder;
    this.idempotency = idempotency;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** 첫 관리자 계정: 설정에 아이디·비밀번호 해시가 있고 그 아이디가 없을 때만 만든다. */
  @EventListener(ApplicationReadyEvent.class)
  public void bootstrap() {
    OperatorProperties.Bootstrap b = props.bootstrap();
    if (b == null || isBlank(b.loginId()) || isBlank(b.passwordHash())) {
      return;
    }
    tx.executeWithoutResult(
        status -> {
          if (store.loginIdExists(b.loginId())) {
            return;
          }
          Instant now = clock.instant();
          store.insertAccount(
              new AccountRow(
                  "OP-" + UUID.randomUUID(),
                  b.loginId(),
                  isBlank(b.displayName()) ? "관리자" : b.displayName(),
                  b.passwordHash(),
                  List.of(props.adminRole()),
                  List.of(),
                  "ACTIVE",
                  0,
                  null,
                  true,
                  null,
                  1,
                  now),
              "bootstrap");
          log.info("첫 운영자 관리자 계정을 만들었습니다(설정값 pmart.auth.operator.bootstrap)");
        });
  }

  public List<AccountRow> list(@Nullable String cursor, int size) {
    requireAdmin();
    return store.list(cursor, size);
  }

  public AccountRow get(String operatorId) {
    requireAdmin();
    return find(operatorId);
  }

  public RequestIdempotency.Outcome<String> create(
      String requestId, String sourceSystem, NewAccount in, Object body) {
    PmartPrincipal admin = requireAdmin();
    validateLoginId(in.loginId());
    List<String> roles = roles(in.roles());
    List<String> centers = distinct(in.centers());
    OperatorRules.passwordProblem(in.initialPassword(), in.loginId(), props.passwordMinLength())
        .ifPresent(
            p -> {
              throw OperatorAuthService.passwordPolicy(p);
            });
    return idempotency.execute(
        key(sourceSystem, requestId, "createOperatorAccount", admin),
        body,
        String.class,
        () -> {
          if (store.loginIdExists(in.loginId())) {
            throw new PmartException(
                "LOGIN_ID_TAKEN", HttpStatus.CONFLICT, "이미 쓰는 아이디입니다.", false, List.of());
          }
          String id = "OP-" + UUID.randomUUID();
          Instant now = clock.instant();
          store.insertAccount(
              new AccountRow(
                  id,
                  in.loginId(),
                  in.displayName(),
                  encoder.encode(in.initialPassword()),
                  roles,
                  centers,
                  "ACTIVE",
                  0,
                  null,
                  true,
                  null,
                  1,
                  now),
              admin.subject());
          log.info("운영자 계정 생성: operatorId={} by {}", id, admin.subject());
          return id;
        });
  }

  public RequestIdempotency.Outcome<String> update(
      String requestId,
      String sourceSystem,
      String operatorId,
      @Nullable Integer expectedVersion,
      Change in,
      Object body) {
    PmartPrincipal admin = requireAdmin();
    List<String> roles = roles(in.roles());
    List<String> centers = distinct(in.centers());
    if (!"ACTIVE".equals(in.state()) && !"DISABLED".equals(in.state())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 ACTIVE 또는 DISABLED 입니다.");
    }
    if (operatorId.equals(admin.subject())
        && (!"ACTIVE".equals(in.state()) || !roles.contains(props.adminRole()))) {
      // 마지막 관리자가 스스로 잠그는 일을 막는다
      throw PmartException.invalidState("자기 계정을 비활성화하거나 관리자 역할을 뺄 수 없습니다.");
    }
    return idempotency.execute(
        key(sourceSystem, requestId, "updateOperatorAccount", admin),
        Map.of("operatorId", operatorId, "body", body),
        String.class,
        () -> {
          AccountRow a = store.byId(operatorId, true).orElseThrow(OperatorAccountService::notFound);
          int expected = expectedVersion == null ? a.version() : expectedVersion;
          Instant now = clock.instant();
          if (!store.updateProfile(
              operatorId, expected, in.displayName(), roles, centers, in.state(), now)) {
            throw PmartException.versionConflict(
                "계정이 이미 바뀌었습니다(기대 버전 " + expected + ", 현재 " + a.version() + ").");
          }
          if ("DISABLED".equals(in.state())) {
            store.revokeAllSessions(operatorId, "ACCOUNT_DISABLED", now);
          }
          log.info("운영자 계정 수정: operatorId={} by {}", operatorId, admin.subject());
          return operatorId;
        });
  }

  public void resetPassword(
      String requestId,
      String sourceSystem,
      String operatorId,
      String temporaryPassword,
      Object body) {
    PmartPrincipal admin = requireAdmin();
    idempotency.execute(
        key(sourceSystem, requestId, "resetOperatorPassword", admin),
        Map.of("operatorId", operatorId, "body", body),
        String.class,
        () -> {
          AccountRow a = store.byId(operatorId, true).orElseThrow(OperatorAccountService::notFound);
          OperatorRules.passwordProblem(temporaryPassword, a.loginId(), props.passwordMinLength())
              .ifPresent(
                  p -> {
                    throw OperatorAuthService.passwordPolicy(p);
                  });
          Instant now = clock.instant();
          store.setPassword(operatorId, encoder.encode(temporaryPassword), true, now);
          store.revokeAllSessions(operatorId, "PASSWORD_RESET", now);
          log.info("운영자 비밀번호 초기화: operatorId={} by {}", operatorId, admin.subject());
          return operatorId;
        });
  }

  public AccountRow find(String operatorId) {
    return store.byId(operatorId, false).orElseThrow(OperatorAccountService::notFound);
  }

  private PmartPrincipal requireAdmin() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> p.roles().contains(props.adminRole()))
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN, "계정 관리 권한(" + props.adminRole() + ")이 없습니다."));
  }

  private static RequestKey key(
      String sourceSystem, String requestId, String operation, PmartPrincipal admin) {
    return new RequestKey(sourceSystem, requestId, operation, admin.subject());
  }

  private static void validateLoginId(String loginId) {
    if (!OperatorRules.LOGIN_ID.matcher(loginId).matches()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "아이디는 영문 소문자·숫자·.·_·- 3~64자입니다(첫 글자는 영문·숫자).");
    }
  }

  private static List<String> roles(List<String> in) {
    List<String> out = distinct(in);
    for (String r : out) {
      if (!OperatorRules.ROLE.matcher(r).matches()) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "역할 이름 형식이 잘못되었습니다: " + r);
      }
    }
    return out;
  }

  private static List<String> distinct(@Nullable List<String> in) {
    return in == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(in));
  }

  private static PmartException notFound() {
    return PmartException.notFound("OPERATOR_NOT_FOUND", "운영자 계정이 없습니다.");
  }

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
