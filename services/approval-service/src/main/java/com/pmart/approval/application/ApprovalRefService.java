package com.pmart.approval.application;

import com.pmart.approval.domain.ApprovalRefRules;
import com.pmart.approval.domain.ApprovalRefRules.Reason;
import com.pmart.approval.infrastructure.ApprovalStore;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.DecisionRow;
import com.pmart.approval.infrastructure.ApprovalStore.UseRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 번호(approvalRef) 확인·한 번 사용(AP-1, 2026-11-17). 승인 번호를 명령에 실어 집행하는 정본(MFC 재고·품질·운영 제한)이 부른다 —
 * {@code /internal/approval-ref-checks}·{@code /internal/approval-ref-uses}.
 *
 * <p>사용은 한 승인에 한 번(표 approval_ref_use, PK = 승인). {@code useRef} 가 업무 멱등 키라 같은 집행의 재시도는 같은 결과, 다른
 * 집행은 ALREADY_USED. 쓸 수 없는 판정은 기록하지 않는다(승인이 난 뒤 같은 useRef 로 다시 오면 다시 판정) — 그래서 RequestIdempotency
 * 응답 재생을 쓰지 않는다.
 */
@Service
public class ApprovalRefService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalRefService.class);

  /** 정본이 적어 보낸 집행(계약 ApprovalRefRequest). */
  public record Request(
      String approvalId,
      String type,
      String targetService,
      String targetRef,
      @Nullable String actorId,
      @Nullable String useRef) {}

  /**
   * 판정 결과. 승인이 없으면 reason = NOT_FOUND 이고 승인·결정·사용은 null.
   *
   * @param approved 승인(APPROVE) 결정 — 승인자·결정 시각
   * @param use 사용 기록(이번 사용, 같은 useRef 의 이전 사용, 또는 ALREADY_USED 의 원인)
   */
  public record Result(
      String approvalId,
      @Nullable Reason reason,
      @Nullable ApprovalRow approval,
      @Nullable DecisionRow approved,
      @Nullable UseRow use) {

    public boolean usable() {
      return reason == null;
    }
  }

  private final ApprovalStore store;
  private final Clock clock;

  public ApprovalRefService(ApprovalStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** 확인만 한다(상태 변화 없음). */
  @Transactional(readOnly = true)
  public Result check(Request q) {
    return evaluate(q);
  }

  /**
   * 확인 + 한 번 사용. 같은 useRef 로 이미 쓴 승인이면 그 기록을 돌려준다(재시도 멱등).
   *
   * @param usedByService 호출자 X-Source-System(자기 신고)
   */
  @Transactional
  public Result use(String usedByService, Request q) {
    if (q.useRef() == null || q.useRef().isBlank()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "승인 번호 사용에는 useRef(사용 멱등 키)가 필요합니다.",
          List.of(new PmartException.Detail("useRef", "필수")));
    }
    Result r = evaluate(q);
    if (!r.usable() || r.use() != null) {
      return r; // 쓸 수 없음, 또는 같은 useRef 로 이미 씀
    }
    UseRow row =
        new UseRow(
            q.approvalId(),
            q.useRef(),
            usedByService,
            q.targetService(),
            q.targetRef(),
            q.actorId(),
            clock.instant());
    if (!store.insertUseIfAbsent(row)) {
      return evaluate(q); // 다른 요청이 먼저 썼다 — 같은 useRef 면 통과, 아니면 ALREADY_USED
    }
    log.info(
        "승인 번호 {} 사용 — {} {} (useRef {}, 호출 {})",
        q.approvalId(),
        q.targetService(),
        q.targetRef(),
        q.useRef(),
        usedByService);
    return new Result(q.approvalId(), null, r.approval(), r.approved(), row);
  }

  private Result evaluate(Request q) {
    ApprovalRow ap = store.findApproval(q.approvalId()).orElse(null);
    if (ap == null) {
      return new Result(q.approvalId(), Reason.NOT_FOUND, null, null, null);
    }
    DecisionRow approved =
        store.decisions(ap.approvalId()).stream()
            .filter(d -> "APPROVE".equals(d.decision()))
            .reduce((a, b) -> b)
            .orElse(null);
    UseRow use = store.findUse(ap.approvalId()).orElse(null);
    Reason reason =
        ApprovalRefRules.reason(
            new ApprovalRefRules.Facts(
                ap.type(),
                ap.targetService(),
                ap.targetRef(),
                ap.reviewState(),
                approved == null ? null : approved.approverId(),
                ap.effectiveFrom(),
                ap.expiresAt(),
                use == null ? null : use.useRef()),
            new ApprovalRefRules.Ask(
                q.type(), q.targetService(), q.targetRef(), q.actorId(), q.useRef()),
            clock.instant());
    return new Result(q.approvalId(), reason, ap, approved, use);
  }
}
