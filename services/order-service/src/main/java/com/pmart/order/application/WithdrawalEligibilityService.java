package com.pmart.order.application;

import com.pmart.order.domain.WithdrawalRules;
import com.pmart.order.infrastructure.DraftStore;
import com.pmart.order.infrastructure.OrchestrationClient;
import com.pmart.order.infrastructure.OrderStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 탈퇴 의무 확인(2026-11-20, ADR-0077 결정 3, 계약 0.16.0) — customer-service 가 부른다. 확정 주문 원장의 끝나지 않은 주문과,
 * 확정 주문이 없는 결제 진입(사가 상태를 orchestration 에 묻는다)을 본다. 결제 진입 전 초안은 의무가 아니다. 읽기만 한다(상태·사건 없음).
 * 개인정보·accountId 로그 금지.
 */
@Service
public class WithdrawalEligibilityService {

  /** 미결 의무 하나(kind = {@link WithdrawalRules} 상수, ref = orderId). */
  public record Obligation(String kind, String ref) {}

  public record Eligibility(boolean eligible, List<Obligation> openObligations, Instant asOf) {}

  private final OrderStore orders;
  private final DraftStore drafts;
  private final OrchestrationClient orchestration;
  private final Clock clock;

  public WithdrawalEligibilityService(
      OrderStore orders, DraftStore drafts, OrchestrationClient orchestration, Clock clock) {
    this.orders = orders;
    this.drafts = drafts;
    this.orchestration = orchestration;
    this.clock = clock;
  }

  public Eligibility check(String accountId) {
    Instant asOf = clock.instant();
    List<Obligation> open = new ArrayList<>();
    for (OrderStore.StatusRow o :
        orders.unfinishedByAccount(accountId, WithdrawalRules.ORDER_FINISHED)) {
      String kind = WithdrawalRules.orderObligation(o.status());
      if (kind != null) {
        open.add(new Obligation(kind, o.orderId()));
      }
    }
    // 확정 주문이 없는 결제 진입 — 사가 조회 실패(null)도 미결(결과를 모른다)
    for (DraftStore.Checkout c : drafts.checkoutsWithoutOrder(accountId)) {
      String kind = WithdrawalRules.checkoutObligation(state(orchestration.saga(c.sagaId())));
      if (kind != null) {
        open.add(new Obligation(kind, c.orderId()));
      }
    }
    return new Eligibility(open.isEmpty(), List.copyOf(open), asOf);
  }

  private static @Nullable String state(@Nullable JsonNode saga) {
    if (saga == null || !saga.hasNonNull("state")) {
      return null;
    }
    return saga.get("state").asString();
  }
}
