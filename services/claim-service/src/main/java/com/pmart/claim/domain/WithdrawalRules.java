package com.pmart.claim.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 고객 탈퇴 가능 여부 — 끝나지 않은 클레임·환불(ADR-0077 결정 3, 계약 0.9.0). customer-service 가 order 와 함께 묻고, 하나라도 미결이면
 * 탈퇴를 완료하지 않는다.
 *
 * <ul>
 *   <li>CLAIM_IN_PROGRESS: 클레임 상태가 끝 상태(CLOSED·REJECTED·CANCELLED·WITHDRAWN)가 아니다. 모르는 상태도 미결로
 *       본다(보수).
 *   <li>REFUND_IN_PROGRESS: 환불 의도 중 COMPLETED 가 아닌 것(실행 대기·PG 결과 대기·불명·FAILED — FAILED 는 돈을 아직 못
 *       돌려준 것이라 운영자 대사 전까지 미결, OMS §환불 "부분 취소 실패·장기 미결이면 종결하지 않는다"). 회수비 늦은 승인 보상 취소 중·실패
 *       (LATE_CANCEL_PENDING·LATE_CANCEL_FAILED)도 돌려줄 돈이라 여기에. 클레임이 끝났는데 회수비 결제 대기(WAITING)가 남아 있으면
 *       결제가 아직 날 수 있어 여기에 넣는다(진행 중 클레임의 WAITING 은 회수비 받기이지 환불이 아니므로 넣지 않는다).
 *   <li>DISPUTE_OPEN: 이 서비스에는 분쟁(사건) 모델이 없어 내지 않는다.
 * </ul>
 *
 * ref 는 모두 claimId. 한 클레임이 두 종류에 다 걸리면 둘 다 낸다.
 */
public final class WithdrawalRules {

  private WithdrawalRules() {}

  public static final String CLAIM_IN_PROGRESS = "CLAIM_IN_PROGRESS";
  public static final String REFUND_IN_PROGRESS = "REFUND_IN_PROGRESS";

  /** 더 바뀌지 않는 클레임 상태. WITHDRAWN 은 아직 쓰지 않지만 유일 인덱스(V2·V3)가 끝 상태로 본다. */
  public static final Set<String> TERMINAL_CLAIM_STATES =
      Set.of("CLOSED", "REJECTED", ReturnRules.CANCELLED, "WITHDRAWN");

  /** 환불 의도의 끝(성공) 상태. 그 밖은 모두 미결. */
  public static final String REFUND_COMPLETED = "COMPLETED";

  /** 돌려줄 돈이 남은 회수비 상태(늦게 승인된 결제의 보상 취소 중·실패). */
  private static final Set<String> FEE_OWED =
      Set.of(ReturnRules.FEE_LATE_CANCEL_PENDING, ReturnRules.FEE_LATE_CANCEL_FAILED);

  /**
   * 클레임 하나의 사실.
   *
   * @param feeState 반품 회수비 상태(없으면 null)
   */
  public record ClaimFacts(
      String claimId, String state, List<String> refundStates, @Nullable String feeState) {}

  public record Obligation(String kind, String ref) {}

  public static boolean terminal(String claimState) {
    return TERMINAL_CLAIM_STATES.contains(claimState);
  }

  /** 미결 의무 목록(클레임 순서대로). 비면 탈퇴 가능. */
  public static List<Obligation> openObligations(List<ClaimFacts> claims) {
    List<Obligation> out = new ArrayList<>();
    for (ClaimFacts c : claims) {
      boolean done = terminal(c.state());
      if (!done) {
        out.add(new Obligation(CLAIM_IN_PROGRESS, c.claimId()));
      }
      if (refundOpen(c, done)) {
        out.add(new Obligation(REFUND_IN_PROGRESS, c.claimId()));
      }
    }
    return out;
  }

  private static boolean refundOpen(ClaimFacts c, boolean claimDone) {
    if (c.refundStates().stream().anyMatch(s -> !REFUND_COMPLETED.equals(s))) {
      return true;
    }
    String fee = c.feeState();
    if (fee == null) {
      return false;
    }
    return FEE_OWED.contains(fee) || (claimDone && ReturnRules.FEE_WAITING.equals(fee));
  }
}
