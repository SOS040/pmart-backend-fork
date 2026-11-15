package com.pmart.backoffice.bff.api;

import com.pmart.backoffice.bff.api.generated.CommandsApi;
import com.pmart.backoffice.bff.api.generated.model.ProxyCommand202Response;
import com.pmart.backoffice.bff.api.generated.model.ProxyCommandRequest;
import com.pmart.backoffice.bff.application.PendingWorkView;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 CommandsApi — 명령 전달(0.2.0, 2026-11-16 BO-12): 지금은 order-orchestration-service 수동 조치 둘만({@link
 * PendingWorkView}). 결과 조회(getProxiedCommandResult)는 아직 501.
 */
@RestController
public class CommandsController implements CommandsApi {

  private final PendingWorkView pendingWork;
  private final ObjectMapper mapper;

  public CommandsController(PendingWorkView pendingWork, ObjectMapper mapper) {
    this.pendingWork = pendingWork;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ProxyCommand202Response> proxyCommand(
      String targetService,
      String command,
      String idempotencyKey,
      ProxyCommandRequest body,
      @Nullable Integer xExpectedVersion,
      @Nullable String traceparent) {
    return ResponseEntity.accepted()
        .body(
            mapper.convertValue(
                pendingWork.command(
                    targetService, command, idempotencyKey, body.getTargetRef(), body.getReason()),
                ProxyCommand202Response.class));
  }
}
