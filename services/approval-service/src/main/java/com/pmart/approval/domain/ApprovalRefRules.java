package com.pmart.approval.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 승인 번호(approvalRef) 확인·사용 규칙(AP-1, 2026-11-17). 정본이 명령에 실린 승인 번호로 집행해도 되는지 판정한다.
 *
 * <p>판정 순서(계약 approval-ref-checks): 종류 → 정본·대상 → 승인됨(APPROVED) → 집행자 ≠ 승인자 → 사용 기록(같은 useRef 면 통과,
 * 다르면 ALREADY_USED) → 효력 시작 → 만료. 같은 useRef 로 이미 쓴 승인은 그 뒤 만료돼도 통과한다 — 쓴 시점에 유효했고, 같은 집행의 재시도이기
 * 때문이다. 부분 승인은 아직 없으므로 APPROVED 만 쓸 수 있다.
 */
public final class ApprovalRefRules {

  /** 쓸 수 없는 이유(계약 ApprovalRefReason). */
  public enum Reason {
    NOT_FOUND,
    TYPE_MISMATCH,
    TARGET_MISMATCH,
    NOT_APPROVED,
    SELF_APPROVAL,
    ALREADY_USED,
    NOT_YET_EFFECTIVE,
    EXPIRED
  }

  /**
   * 저장된 승인 쪽 사실.
   *
   * @param approverId 승인 결정자(APPROVE 결정이 없으면 null)
   * @param usedRef 사용 기록의 useRef(아직 안 썼으면 null)
   */
  public record Facts(
      String type,
      String targetService,
      String targetRef,
      String reviewState,
      @Nullable String approverId,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      @Nullable String usedRef) {}

  /** 정본이 적어 보낸 집행. */
  public record Ask(
      String type,
      String targetService,
      String targetRef,
      @Nullable String actorId,
      @Nullable String useRef) {}

  private ApprovalRefRules() {}

  /** null = 쓸 수 있다. */
  public static @Nullable Reason reason(Facts f, Ask q, Instant now) {
    if (!f.type().equals(q.type())) {
      return Reason.TYPE_MISMATCH;
    }
    if (!f.targetService().equals(q.targetService()) || !f.targetRef().equals(q.targetRef())) {
      return Reason.TARGET_MISMATCH;
    }
    if (!"APPROVED".equals(f.reviewState())) {
      return Reason.NOT_APPROVED;
    }
    if (q.actorId() != null && q.actorId().equals(f.approverId())) {
      return Reason.SELF_APPROVAL;
    }
    if (f.usedRef() != null) {
      return f.usedRef().equals(q.useRef()) ? null : Reason.ALREADY_USED;
    }
    if (f.effectiveFrom() != null && now.isBefore(f.effectiveFrom())) {
      return Reason.NOT_YET_EFFECTIVE;
    }
    if (f.expiresAt() != null && !now.isBefore(f.expiresAt())) {
      return Reason.EXPIRED;
    }
    return null;
  }
}
