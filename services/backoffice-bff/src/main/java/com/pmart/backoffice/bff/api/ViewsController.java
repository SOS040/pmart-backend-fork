package com.pmart.backoffice.bff.api;

import com.pmart.backoffice.bff.api.generated.ViewsApi;
import com.pmart.backoffice.bff.api.generated.model.GetApprovalInboxView200Response;
import com.pmart.backoffice.bff.application.ApprovalInboxView;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 ViewsApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고
 * 여기서는 요청·응답 변환만 한다. 구현: 승인함(2026-11-03, BR-40).
 */
@RestController
public class ViewsController implements ViewsApi {

  private final ApprovalInboxView approvalInbox;
  private final ObjectMapper mapper;

  public ViewsController(ApprovalInboxView approvalInbox, ObjectMapper mapper) {
    this.approvalInbox = approvalInbox;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<GetApprovalInboxView200Response> getApprovalInboxView(
      @Nullable String approvalType,
      @Nullable String reviewState,
      @Nullable String centerId,
      Integer size,
      @Nullable String cursor) {
    return ResponseEntity.ok(
        mapper.convertValue(
            approvalInbox.view(approvalType, reviewState, centerId, size, cursor),
            GetApprovalInboxView200Response.class));
  }
}
