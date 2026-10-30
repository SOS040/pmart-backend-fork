package com.pmart.delivery.capacity.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 슬롯 점유(주문당 1건). HELD → CONFIRMED(주문 확정) / RELEASED(보상·취소·운영) / EXPIRED(TTL 경과).
 *
 * <ul>
 *   <li>HELD 는 만료 시각이 지나면 정리 작업이 돌기 전이라도 정원에서 빠지고 EXPIRED 로 보인다 — 그래서 만료된 점유를 뒤늦게 확정해 정원을 넘기는 일이
 *       없다.
 *   <li>확정: HELD → CONFIRMED, 이미 CONFIRMED 면 그대로 성공, RELEASED·EXPIRED 면 거절(재확보 필요).
 *   <li>해제: HELD·CONFIRMED → RELEASED(정원 반환), 이미 RELEASED·EXPIRED 면 그대로 성공. OPERATOR 사유는 승인 근거가 있어야
 *       한다.
 * </ul>
 */
public record SlotHold(
    String holdId,
    int version,
    String orderId,
    @Nullable String draftId,
    @Nullable String sagaId,
    String mfcId,
    SlotRules.Window window,
    State state,
    Instant heldAt,
    Instant expiresAt,
    @Nullable Instant confirmedAt,
    @Nullable Instant releasedAt,
    @Nullable String releaseReason) {

  public enum State {
    HELD,
    CONFIRMED,
    RELEASED,
    EXPIRED
  }

  /** 명령 판정: 반영 / 이미 그 상태(멱등 성공) / 거절. */
  public enum Decision {
    APPLY,
    ALREADY_DONE,
    INVALID
  }

  public static final String REASON_OPERATOR = "OPERATOR";

  public String slotId() {
    return SlotRules.slotId(mfcId, window);
  }

  /** 시각 now 에서 보이는 상태(만료 시각이 지난 HELD 는 EXPIRED). */
  public State stateAt(Instant now) {
    return state == State.HELD && !now.isBefore(expiresAt) ? State.EXPIRED : state;
  }

  /** 시각 now 에서 보이는 모습. */
  public SlotHold observedAt(Instant now) {
    State s = stateAt(now);
    return s == state
        ? this
        : new SlotHold(
            holdId,
            version,
            orderId,
            draftId,
            sagaId,
            mfcId,
            window,
            s,
            heldAt,
            expiresAt,
            confirmedAt,
            releasedAt,
            releaseReason);
  }

  public Decision confirmDecision(Instant now) {
    return switch (stateAt(now)) {
      case HELD -> Decision.APPLY;
      case CONFIRMED -> Decision.ALREADY_DONE;
      case RELEASED, EXPIRED -> Decision.INVALID;
    };
  }

  public Decision releaseDecision(Instant now) {
    return switch (stateAt(now)) {
      case HELD, CONFIRMED -> Decision.APPLY;
      case RELEASED, EXPIRED -> Decision.ALREADY_DONE;
    };
  }

  /** OPERATOR 사유 해제는 승인 근거(approvalRef) 필수. */
  public static boolean approvalMissing(String reason, @Nullable String approvalRef) {
    return REASON_OPERATOR.equals(reason) && (approvalRef == null || approvalRef.isBlank());
  }
}
