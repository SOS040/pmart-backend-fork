package com.pmart.approval.api;

import com.pmart.approval.api.generated.ChangeRequestsApi;
import com.pmart.approval.api.generated.model.ChangeRequest;
import com.pmart.approval.api.generated.model.ChangeRequestBody;
import com.pmart.approval.api.generated.model.SubmitChangeRequest202Response;
import com.pmart.approval.application.ApprovalService;
import com.pmart.approval.application.ApprovalService.NewChangeRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ChangeRequestsApi 의 구현 진입점. 아직 구현하지 않은 메서드(철회)는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class ChangeRequestsController implements ChangeRequestsApi {

  private final ApprovalService service;
  private final ApiMapping mapping;

  public ChangeRequestsController(ApprovalService service, ApiMapping mapping) {
    this.service = service;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<ChangeRequest> createChangeRequest(
      String idempotencyKey, String xSourceSystem, ChangeRequestBody body) {
    var outcome =
        service.createChangeRequest(
            idempotencyKey,
            ApiMapping.operator(xSourceSystem),
            new NewChangeRequest(
                body.getType().getValue(), body.getTargetService(), body.getTargetRef(), body));
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.changeRequest(outcome.value()));
  }

  @Override
  public ResponseEntity<ChangeRequest> getChangeRequest(String changeRequestId) {
    return ResponseEntity.ok(mapping.changeRequest(service.getChangeRequest(changeRequestId)));
  }

  @Override
  public ResponseEntity<SubmitChangeRequest202Response> submitChangeRequest(
      String changeRequestId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    var outcome =
        service.submit(
            idempotencyKey, ApiMapping.operator(xSourceSystem), changeRequestId, xExpectedVersion);
    return ResponseEntity.accepted().body(ApiMapping.command(outcome.value()));
  }
}
