package com.pmart.auth.application;

import com.pmart.auth.domain.WithdrawnIdentifierRules;
import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.IdTokenVerifier;
import com.pmart.auth.infrastructure.IdTokenVerifiers;
import com.pmart.auth.infrastructure.KakaoUnlinkClient;
import com.pmart.auth.infrastructure.SessionStore;
import com.pmart.auth.infrastructure.WithdrawnIdentifierStore;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 고객 탈퇴(2026-11-20 ADR-0077) 중 auth 몫.
 *
 * <ul>
 *   <li>재인증 확인({@code POST /internal/customers/{accountId}/reauthentications}): 로그인과 같은 ID 토큰 검증 +
 *       그 제공자 계정이 이 accountId 에 연결됐는지. 상태를 바꾸지 않는다.
 *   <li>{@code CustomerWithdrawalCompleted} 반영(inbox 트랜잭션 안): 세션 끊기·계정 WITHDRAWN·소셜 식별값 해시를 격리 표로
 *       옮기고 연결 삭제(같은 소셜은 다음 로그인 때 새 계정).
 * </ul>
 *
 * 토큰·sub 는 로그 금지.
 */
@Service
public class CustomerWithdrawalService {

  public static final String COMPLETED = "CustomerWithdrawalCompleted";

  private static final Logger log = LoggerFactory.getLogger(CustomerWithdrawalService.class);

  private final IdTokenVerifiers verifiers;
  private final CustomerStore customers;
  private final SessionStore sessions;
  private final SessionBlocks blocks;
  private final WithdrawnIdentifierStore withdrawn;
  private final Clock clock;
  private final KakaoUnlinkClient kakao;

  public CustomerWithdrawalService(
      IdTokenVerifiers verifiers,
      CustomerStore customers,
      SessionStore sessions,
      SessionBlocks blocks,
      WithdrawnIdentifierStore withdrawn,
      Clock clock,
      KakaoUnlinkClient kakao) {
    this.kakao = kakao;
    this.verifiers = verifiers;
    this.customers = customers;
    this.sessions = sessions;
    this.blocks = blocks;
    this.withdrawn = withdrawn;
    this.clock = clock;
  }

  /**
   * 재인증 확인. 토큰 거절 401 PROVIDER_REJECTED, 제공자 키 조회 실패 503, 연결 없음·다른 계정 403
   * REAUTHENTICATION_MISMATCH.
   */
  public void verifyReauthentication(String accountId, String provider, String providerToken) {
    IdTokenVerifier verifier = verifiers.require(provider);
    IdTokenVerifier.Identity identity;
    try {
      identity = verifier.verify(providerToken);
    } catch (IdTokenVerifier.Unavailable e) {
      throw CustomerSocialLoginService.providerUnavailable(e);
    } catch (IdTokenVerifier.Rejected e) {
      log.warn("재인증 거절(PROVIDER_REJECTED) provider={} accountId={}", provider, accountId);
      throw CustomerSocialLoginService.providerRejected();
    }
    Optional<String> linked = customers.linkedAccount(provider, identity.subject());
    if (linked.isEmpty() || !linked.get().equals(accountId)) {
      log.warn("재인증 계정 불일치 provider={} accountId={}", provider, accountId);
      throw new PmartException(
          "REAUTHENTICATION_MISMATCH",
          HttpStatus.FORBIDDEN,
          "이 계정에 연결된 소셜 계정이 아닙니다. 가입한 소셜 계정으로 다시 로그인하세요.",
          false,
          List.of());
    }
  }

  /**
   * CustomerWithdrawalCompleted 반영. 호출자(inbox)의 트랜잭션 안에서 돈다. 이미 WITHDRAWN 이거나 auth 에 없는 계정이면 아무것도
   * 하지 않는다(재전달·재처리 안전).
   *
   * @return 반영했으면 true
   */
  public boolean onWithdrawalCompleted(EventEnvelope e) {
    String accountId = e.payload().path("accountId").asString("");
    if (accountId.isBlank()) {
      throw new IllegalArgumentException("CustomerWithdrawalCompleted accountId 없음 " + e.eventId());
    }
    Optional<String> state = customers.lockAccountState(accountId);
    if (state.isEmpty()) {
      log.info("탈퇴 완료 — auth 에 없는 계정이라 건너뜀 accountId={}", accountId);
      return false;
    }
    if ("WITHDRAWN".equals(state.get())) {
      return false;
    }
    Instant now = clock.instant();
    Instant withdrawnAt = completedAt(e, now);
    Instant expiresAt = WithdrawnIdentifierRules.expiresAt(withdrawnAt);
    List<CustomerStore.LinkedIdentity> links = customers.identities(accountId);
    for (CustomerStore.LinkedIdentity l : links) {
      withdrawn.save(
          WithdrawnIdentifierRules.hash(l.provider(), l.subject()),
          l.provider(),
          withdrawnAt,
          expiresAt);
    }
    customers.deleteIdentities(accountId);
    customers.markWithdrawn(accountId);
    sessions.revokeAllSessions(accountId, "WITHDRAWN", now);
    // 이미 나간 access token 도 게이트웨이에서 바로 막는다(SubjectBlocked, 같은 트랜잭션 outbox)
    blocks.block(
        accountId,
        null,
        CustomerSocialLoginService.TYPE,
        now,
        "ACCOUNT_DISABLED",
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "auth-service"));
    log.info("고객 탈퇴 반영 accountId={} 연결 {}개 정리", accountId, links.size());
    // 카카오는 연결된 동안 동의를 기억한다 — 끊어 두면 다시 가입할 때 동의 화면이 뜬다(2026-11-21 사용자 "처음 가입할 때만").
    // 커밋 뒤 한 번, 실패는 로그만(탈퇴는 그대로). 재처리로 다시 와도 위에서 WITHDRAWN 이라 다시 부르지 않는다.
    List<String> kakaoIds =
        links.stream().filter(l -> "KAKAO".equals(l.provider())).map(l -> l.subject()).toList();
    if (!kakaoIds.isEmpty()) {
      Runnable unlink =
          () -> kakaoIds.forEach(id -> log.info("카카오 연결 끊기(탈퇴) ok={}", kakao.unlink(id)));
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                unlink.run();
              }
            });
      } else {
        unlink.run();
      }
    }
    return true;
  }

  private static Instant completedAt(EventEnvelope e, Instant fallback) {
    String v = e.payload().path("completedAt").asString("");
    if (v.isBlank()) {
      return e.occurredAt() != null ? e.occurredAt() : fallback;
    }
    try {
      return Instant.parse(v);
    } catch (DateTimeParseException ex) {
      return java.time.OffsetDateTime.parse(v).toInstant();
    }
  }
}
