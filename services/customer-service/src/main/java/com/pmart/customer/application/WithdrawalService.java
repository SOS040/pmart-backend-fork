package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.WithdrawalClients;
import com.pmart.customer.infrastructure.WithdrawalClients.Eligibility;
import com.pmart.customer.infrastructure.WithdrawalClients.Obligation;
import com.pmart.customer.infrastructure.WithdrawalStore;
import com.pmart.customer.infrastructure.WithdrawalStore.WithdrawalRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 탈퇴(2026-11-20 ADR-0077, Commerce §4.4·OMS §13).
 *
 * <ul>
 *   <li>접수: 같은 소셜 재인증(auth 확인, 트랜잭션 밖) → ACTIVE 만 WITHDRAW_PENDING, 탈퇴 요청 PENDING,
 *       CustomerWithdrawalRequested. 주문·예약을 자동 취소하지 않는다(구매 가능 = ACTIVE 라 신규 구매만 막힘).
 *   <li>확인({@link #check}): order·claim 의무 조회(트랜잭션 밖) → 둘 다 eligible 이면 완료, 미결이 있으면
 *       BLOCKED_BY_OBLIGATIONS, 조회 실패는 UNKNOWN(거래 없음으로 추정하지 않음). 접수 직후 한 번, 그 뒤 {@link
 *       WithdrawalJobs} 가 주기로.
 *   <li>완료: 개인정보 지움·확인 번호 풀기·WITHDRAW_COMPLETED·CustomerWithdrawalCompleted(auth 세션·소셜 연결, cart,
 *       notification) 한 트랜잭션, 사진 사본(S3)은 커밋 뒤.
 *   <li>철회: 재인증 뒤 진행 중 탈퇴만 — ACTIVE 로. 마케팅 동의는 되돌리지 않는다(별도 재동의).
 * </ul>
 *
 * 토큰·사유 원문·개인정보는 로그에 남기지 않는다.
 */
@Service
public class WithdrawalService {

  public static final String OPERATION_REQUEST = "requestWithdrawal";
  public static final String OPERATION_REVOKE = "revokeWithdrawal";
  private static final Set<String> PROVIDERS = Set.of("GOOGLE", "KAKAO", "NAVER", "APPLE");
  private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

  /** 응답(계약 Withdrawal). */
  public record View(
      String withdrawalId,
      String state,
      Instant requestedAt,
      @Nullable Instant completedAt,
      @Nullable Instant revokedAt,
      @Nullable Instant lastCheckedAt,
      List<Obligation> openObligations) {}

  private final CustomerStore customers;
  private final WithdrawalStore store;
  private final WithdrawalClients clients;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectProvider<ProfileImageStorage> images;
  private final ObjectMapper mapper;
  private final Clock clock;

  public WithdrawalService(
      CustomerStore customers,
      WithdrawalStore store,
      WithdrawalClients clients,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectProvider<ProfileImageStorage> images,
      ObjectMapper mapper,
      Clock clock) {
    this.customers = customers;
    this.store = store;
    this.clients = clients;
    this.tx = tx;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.images = images;
    this.mapper = mapper;
    this.clock = clock;
  }

  public View request(
      String accountId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      String provider,
      String providerToken,
      @Nullable String reasonCode,
      @Nullable String reasonText) {
    checkProvider(provider, providerToken);
    // 같은 키 재시도(응답 유실)는 저장된 결과 — 재인증 전에 확인하지 않는다(토큰은 매번 새로 받아 오므로 해시에 넣지 않는다)
    clients.reauthenticate(accountId, provider, providerToken);
    customers.ensureProfile(accountId, clock.instant());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", provider);
    body.put("reasonCode", reasonCode);
    body.put("reasonText", reasonText);
    View v =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, OPERATION_REQUEST, accountId),
                body,
                View.class,
                () -> accept(accountId, expectedVersion, reasonCode, reasonText))
            .value();
    if ("PENDING".equals(v.state())) {
      check(v.withdrawalId()); // 의무가 없으면 바로 완료(그 사이 실패는 주기 작업이 다시)
      return view(store.find(v.withdrawalId()).orElseThrow());
    }
    return v;
  }

  private View accept(
      String accountId,
      @Nullable Integer expectedVersion,
      @Nullable String reasonCode,
      @Nullable String reasonText) {
    Instant now = clock.instant();
    ProfileRow p =
        customers
            .lockProfile(accountId)
            .orElseThrow(() -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
    if (expectedVersion != null && expectedVersion != p.version()) {
      throw PmartException.versionConflict("계정 버전이 다릅니다.");
    }
    if (!"ACTIVE".equals(p.status())) {
      throw PmartException.invalidState("이미 탈퇴를 신청했거나 탈퇴한 계정입니다.");
    }
    String id = "WDR-" + UUID.randomUUID();
    store.insert(id, accountId, trim(reasonCode, 40), trim(reasonText, 500), now);
    store.setAccountStatus(accountId, "WITHDRAW_PENDING", now);
    publish(
        "CustomerWithdrawalRequested",
        accountId,
        p.version() + 1,
        Map.of("requestedAt", now.toString()));
    log.info("고객 탈퇴 접수 {} {}", accountId, id);
    return view(store.find(id).orElseThrow());
  }

  public List<View> list(String accountId) {
    return store.byAccount(accountId).stream().map(this::view).toList();
  }

  public View get(String accountId, String withdrawalId) {
    return store
        .find(withdrawalId)
        .filter(w -> w.accountId().equals(accountId))
        .map(this::view)
        .orElseThrow(() -> PmartException.notFound("WITHDRAWAL_NOT_FOUND", "탈퇴 요청이 없습니다."));
  }

  public View revoke(
      String accountId,
      String withdrawalId,
      String requestId,
      String sourceSystem,
      String provider,
      String providerToken) {
    checkProvider(provider, providerToken);
    get(accountId, withdrawalId); // 남의 것·없는 것은 재인증 전에 404
    clients.reauthenticate(accountId, provider, providerToken);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("withdrawalId", withdrawalId);
    body.put("provider", provider);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OPERATION_REVOKE, accountId),
            body,
            View.class,
            () -> {
              Instant now = clock.instant();
              WithdrawalRow w = store.lock(withdrawalId).orElseThrow();
              if (!WithdrawalStore.OPEN.contains(w.state())) {
                throw PmartException.invalidState("진행 중인 탈퇴만 철회할 수 있습니다.");
              }
              customers.lockProfile(accountId);
              store.markRevoked(withdrawalId, now);
              store.setAccountStatus(accountId, "ACTIVE", now);
              log.info("고객 탈퇴 철회 {} {}", accountId, withdrawalId);
              return view(store.find(withdrawalId).orElseThrow());
            })
        .value();
  }

  /** 진행 중 탈퇴 하나를 확인하고 결과를 남긴다(완료·대기·불명). 의무 조회는 트랜잭션 밖. */
  public void check(String withdrawalId) {
    WithdrawalRow before = store.find(withdrawalId).orElse(null);
    if (before == null || !WithdrawalStore.OPEN.contains(before.state())) {
      return;
    }
    String accountId = before.accountId();
    Eligibility order = clients.order(accountId);
    Eligibility claim = clients.claim(accountId);
    String imageKey =
        tx.execute(
            status -> {
              Instant now = clock.instant();
              WithdrawalRow w = store.lock(withdrawalId).orElseThrow();
              if (!WithdrawalStore.OPEN.contains(w.state())) {
                return null; // 그 사이 철회·완료
              }
              List<Obligation> open = new ArrayList<>(order.obligations());
              open.addAll(claim.obligations());
              if (order.known() && claim.known() && order.eligible() && claim.eligible()) {
                ProfileRow p = customers.lockProfile(accountId).orElse(null);
                String key = store.erasePersonalData(accountId, now);
                store.markCompleted(withdrawalId, now);
                publish(
                    "CustomerWithdrawalCompleted",
                    accountId,
                    p == null ? 1 : p.version() + 1,
                    Map.of("completedAt", now.toString()));
                log.info("고객 탈퇴 완료 {} {}", accountId, withdrawalId);
                return key;
              }
              String next = open.isEmpty() ? "UNKNOWN" : "BLOCKED_BY_OBLIGATIONS";
              store.markChecked(withdrawalId, next, mapper.writeValueAsString(open), now);
              if (!next.equals(w.state())) {
                log.info("고객 탈퇴 {} → {} (미결 {}건)", withdrawalId, next, open.size());
              }
              return null;
            });
    if (imageKey != null) {
      deleteImage(imageKey);
    }
  }

  private void deleteImage(String key) {
    ProfileImageStorage s = images.getIfAvailable();
    if (s == null) {
      return;
    }
    Runnable r =
        () -> {
          try {
            s.delete(key);
          } catch (RuntimeException e) {
            log.warn("탈퇴 고객 프로필 사진 사본 지우기 실패: {}", e.getClass().getSimpleName());
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              r.run();
            }
          });
    } else {
      r.run();
    }
  }

  private void publish(String type, String accountId, long version, Map<String, Object> extra) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", accountId);
    payload.putAll(extra);
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Customer",
            accountId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, accountId),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(envelope, AddressService.TOPIC);
  }

  View view(WithdrawalRow w) {
    List<Obligation> open =
        mapper.readValue(w.openObligationsJson(), new TypeReference<List<Obligation>>() {});
    return new View(
        w.withdrawalId(),
        w.state(),
        w.requestedAt(),
        w.completedAt(),
        w.revokedAt(),
        w.lastCheckedAt(),
        open);
  }

  private static void checkProvider(String provider, String providerToken) {
    if (provider == null || !PROVIDERS.contains(provider)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "provider 는 GOOGLE·KAKAO·NAVER·APPLE 중 하나입니다.");
    }
    if (providerToken == null || providerToken.isBlank()) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "다시 로그인한 providerToken 이 필요합니다.");
    }
  }

  private static @Nullable String trim(@Nullable String s, int max) {
    if (s == null || s.isBlank()) {
      return null;
    }
    String t = s.strip();
    return t.length() > max ? t.substring(0, max) : t;
  }
}
