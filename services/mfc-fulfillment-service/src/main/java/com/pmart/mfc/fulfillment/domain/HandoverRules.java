package com.pmart.mfc.fulfillment.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 라이더 인계 규칙(WK-08·IV-08, ADR-0047, docs/business/flows/package-handover-contract.md). 순수 계산.
 *
 * <ul>
 *   <li>fence 는 확정 요청(인계 사실)이 생기면 만료와 상관없이 살아 있다 — 결과를 알기 전에는 취소·재인계 모두 막는다("만료 ≠ 취소 성공").
 *   <li>확정 요청 전 fence 는 만료 시각이 지나면 끝난 것으로 본다(다시 찍기·중지 가능).
 * </ul>
 */
public final class HandoverRules {

  private HandoverRules() {}

  public static final String ALREADY_HANDED_OVER = "ALREADY_HANDED_OVER";
  public static final String PACKAGE_INVALIDATED = "PACKAGE_INVALIDATED";
  public static final String INVALID_STATE = "INVALID_STATE";
  public static final String DELIVERY_MISMATCH = "DELIVERY_MISMATCH";
  public static final String NOT_ASSIGNED = "NOT_ASSIGNED";
  public static final String RIDER_MISMATCH = "RIDER_MISMATCH";
  public static final String FENCE_ACTIVE = "FENCE_ACTIVE";
  public static final String FENCE_EXPIRED_UNCONFIRMED = "FENCE_EXPIRED_UNCONFIRMED";
  public static final String HANDOVER_RESULT_PENDING = "HANDOVER_RESULT_PENDING";
  public static final String FENCE_EXPIRED = "FENCE_EXPIRED";
  public static final String FENCE_VERSION_MISMATCH = "FENCE_VERSION_MISMATCH";

  /** 포장별 인계 판정(중지·조회). */
  public enum Verdict {
    READY,
    STOPPED,
    HANDOVER_IN_PROGRESS,
    HANDED_OVER
  }

  /** fence 가 살아 있는가: 확정 요청이 있으면(결과 무관, APPLIED 는 포장이 이미 HANDED_OVER) 늘, 아니면 만료 전까지. */
  public static boolean fenceLive(
      String fenceState, @Nullable Instant expiresAt, boolean commitRequested, Instant now) {
    if (!"HANDOVER_IN_PROGRESS".equals(fenceState)) {
      return false;
    }
    return commitRequested || (expiresAt != null && now.isBefore(expiresAt));
  }

  /** 포장의 지금 판정. */
  public static Verdict verdict(String packageState, boolean fenceLive) {
    if ("HANDED_OVER".equals(packageState)) {
      return Verdict.HANDED_OVER;
    }
    if (fenceLive) {
      return Verdict.HANDOVER_IN_PROGRESS;
    }
    if ("INVALIDATED".equals(packageState) || "VOIDED".equals(packageState)) {
      return Verdict.STOPPED;
    }
    return Verdict.READY;
  }

  /** 허가 요청 사실. */
  public record AuthorizeFacts(
      boolean qrMatches,
      String packageState,
      String shipmentState,
      boolean orderStopped,
      @Nullable String assignmentShipmentId,
      String packageShipmentId,
      @Nullable String assignmentState,
      @Nullable String assignedRiderId,
      String riderId,
      boolean fenceLive,
      boolean commitRequested,
      @Nullable String fenceRiderId,
      @Nullable String fenceDeliveryId,
      String deliveryId,
      boolean fenceOpen) {}

  /** 허가 판정: REJECT(code) / REUSE(같은 라이더·배송의 살아 있는 fence 그대로) / NEW. */
  public record AuthorizeDecision(String outcome, @Nullable String code) {
    public static AuthorizeDecision reject(String code) {
      return new AuthorizeDecision("REJECT", code);
    }
  }

  public static AuthorizeDecision authorize(AuthorizeFacts f) {
    if ("HANDED_OVER".equals(f.packageState())) {
      return AuthorizeDecision.reject(ALREADY_HANDED_OVER);
    }
    if (!f.qrMatches()
        || "INVALIDATED".equals(f.packageState())
        || "VOIDED".equals(f.packageState())) {
      return AuthorizeDecision.reject(PACKAGE_INVALIDATED);
    }
    if (!"SEALED".equals(f.packageState())
        || f.orderStopped()
        || !("READY".equals(f.shipmentState())
            || "PARTIALLY_HANDED_OVER".equals(f.shipmentState()))) {
      return AuthorizeDecision.reject(INVALID_STATE);
    }
    if (f.assignmentState() == null || !f.packageShipmentId().equals(f.assignmentShipmentId())) {
      return AuthorizeDecision.reject(DELIVERY_MISMATCH);
    }
    if (!"ASSIGNED".equals(f.assignmentState()) || f.assignedRiderId() == null) {
      return AuthorizeDecision.reject(NOT_ASSIGNED);
    }
    if (!f.assignedRiderId().equals(f.riderId())) {
      return AuthorizeDecision.reject(RIDER_MISMATCH);
    }
    if (f.commitRequested()) {
      return AuthorizeDecision.reject(HANDOVER_RESULT_PENDING);
    }
    boolean same =
        f.riderId().equals(f.fenceRiderId()) && f.deliveryId().equals(f.fenceDeliveryId());
    if (f.fenceLive()) {
      return same ? new AuthorizeDecision("REUSE", null) : AuthorizeDecision.reject(FENCE_ACTIVE);
    }
    if (f.fenceOpen() && !same) {
      // 만료됐지만 실제로 안 넘겼다는 확인이 없다 — 같은 라이더·배송만 다시 찍기(D-1 안 C), 나머지는 점원 확인 뒤
      return AuthorizeDecision.reject(FENCE_EXPIRED_UNCONFIRMED);
    }
    return new AuthorizeDecision("NEW", null);
  }

  /** 점원 확인이 필요한가: 허가 기록이 열려 있고(HANDOVER_IN_PROGRESS) 확정 요청 없이 유효시간이 끝남(D-1). */
  public static boolean checkRequired(
      String fenceState, @Nullable Instant expiresAt, boolean commitRequested, Instant now) {
    return "HANDOVER_IN_PROGRESS".equals(fenceState)
        && !commitRequested
        && (expiresAt == null || !now.isBefore(expiresAt));
  }

  /** QR 원문 = 밀봉 때 발급한 qrCode(`PKG:<id>:<버전>`). */
  public static boolean qrMatches(@Nullable String issued, String scanned) {
    return issued != null && issued.equals(scanned.trim());
  }
}
