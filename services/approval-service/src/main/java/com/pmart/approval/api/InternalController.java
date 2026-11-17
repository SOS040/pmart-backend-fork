package com.pmart.approval.api;

import com.pmart.approval.api.generated.InternalApi;
import com.pmart.approval.api.generated.model.ApprovalRefRequest;
import com.pmart.approval.api.generated.model.ApprovalRefResult;
import com.pmart.approval.application.ApprovalRefService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 의 구현 진입점(서비스 간, 토큰 없음 — ADR-0026). 아직 구현하지 않은 메서드(동기 회신)는 인터페이스 default 가 501 을
 * 돌려준다. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final ApprovalRefService refs;
  private final ApiMapping mapping;

  public InternalController(ApprovalRefService refs, ApiMapping mapping) {
    this.refs = refs;
    this.mapping = mapping;
  }

  /** 승인 번호 확인(AP-1) — 상태 변화 없음. Idempotency-Key 는 공통 헤더 검사용이라 쓰지 않는다. */
  @Override
  public ResponseEntity<ApprovalRefResult> checkApprovalRef(
      String idempotencyKey, String xSourceSystem, ApprovalRefRequest body) {
    return ResponseEntity.ok(mapping.approvalRef(refs.check(request(body))));
  }

  /** 승인 번호 한 번 사용(AP-1) — 멱등은 본문 useRef(Idempotency-Key 응답 재생 없음, 계약 설명). */
  @Override
  public ResponseEntity<ApprovalRefResult> useApprovalRef(
      String idempotencyKey, String xSourceSystem, ApprovalRefRequest body) {
    return ResponseEntity.ok(mapping.approvalRef(refs.use(xSourceSystem, request(body))));
  }

  private static ApprovalRefService.Request request(ApprovalRefRequest b) {
    return new ApprovalRefService.Request(
        b.getApprovalId(),
        b.getType().getValue(),
        b.getTargetService(),
        b.getTargetRef(),
        b.getActorId(),
        b.getUseRef());
  }
}
