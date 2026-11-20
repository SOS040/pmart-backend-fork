package com.pmart.claim.application;

import com.pmart.claim.domain.WithdrawalRules;
import com.pmart.claim.infrastructure.ClaimStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 탈퇴 가능 여부(ADR-0077 결정 3, 계약 0.9.0 {@code GET
 * /internal/accounts/{accountId}/withdrawal-eligibility}). 호출자는 customer-service(내부 경로 — 서비스 주체).
 * 판정 규칙은 {@link WithdrawalRules}. 클레임이 없는 고객도 eligible.
 */
@Service
public class WithdrawalEligibilityService {

  public record Result(
      boolean eligible, List<WithdrawalRules.Obligation> openObligations, Instant asOf) {}

  private final ClaimStore store;
  private final Clock clock;

  public WithdrawalEligibilityService(ClaimStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Result check(String accountId) {
    Instant now = clock.instant();
    List<WithdrawalRules.Obligation> open =
        WithdrawalRules.openObligations(store.withdrawalFacts(accountId));
    return new Result(open.isEmpty(), open, now);
  }
}
