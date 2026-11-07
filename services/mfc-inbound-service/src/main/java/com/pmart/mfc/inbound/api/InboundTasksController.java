package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.InboundTasksApi;
import com.pmart.mfc.inbound.api.generated.model.InboundTask;
import com.pmart.mfc.inbound.api.generated.model.InboundTaskExecution;
import com.pmart.mfc.inbound.api.generated.model.InspectInboundTaskRequest;
import com.pmart.mfc.inbound.api.generated.model.ListInboundTasks200Response;
import com.pmart.mfc.inbound.api.generated.model.PutawayInboundTaskRequest;
import com.pmart.mfc.inbound.application.InboundTaskService;
import com.pmart.mfc.inbound.application.InboundTaskService.ExecutionView;
import com.pmart.mfc.inbound.application.InboundTaskService.InspectResult;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InboundTasksApi — 입고 작업(품질검사·적치). 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class InboundTasksController implements InboundTasksApi {

  private final InboundTaskService service;

  public InboundTasksController(InboundTaskService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListInboundTasks200Response> listInboundTasks(
      String mfcId, @Nullable String state, @Nullable String taskType, @Nullable String receiptId) {
    var out = new ListInboundTasks200Response();
    out.setItems(
        service.list(mfcId, state, taskType, receiptId).stream()
            .map(InboundTasksController::dto)
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<InboundTaskExecution> inspectInboundTask(
      String taskId, String idempotencyKey, String xSourceSystem, InspectInboundTaskRequest body) {
    var results =
        body.getResults().stream()
            .map(
                r ->
                    new InspectResult(
                        r.getQty(),
                        r.getResultQuality().getValue(),
                        r.getSourceLotRef(),
                        r.getExpiryDate()))
            .toList();
    return ResponseEntity.ok(dto(service.inspect(idempotencyKey, xSourceSystem, taskId, results)));
  }

  @Override
  public ResponseEntity<InboundTaskExecution> putawayInboundTask(
      String taskId, String idempotencyKey, String xSourceSystem, PutawayInboundTaskRequest body) {
    return ResponseEntity.ok(
        dto(
            service.putaway(
                idempotencyKey, xSourceSystem, taskId, body.getToLocationCode(), body.getQty())));
  }

  static InboundTaskExecution dto(ExecutionView v) {
    var d = new InboundTaskExecution();
    d.setExecutionId(v.executionId());
    d.setTaskId(v.taskId());
    d.setApplyStatus(InboundTaskExecution.ApplyStatusEnum.fromValue(v.applyStatus()));
    d.setInventoryCommitId(v.commitId());
    d.setRejectReason(v.rejectReason());
    d.setCreatedTaskIds(v.createdTaskIds());
    d.setTask(dto(v.task()));
    return d;
  }

  static InboundTask dto(TaskRow t) {
    var d = new InboundTask();
    d.setTaskId(t.taskId());
    d.setTaskType(InboundTask.TaskTypeEnum.fromValue(t.taskType()));
    d.setMfcId(t.mfcId());
    d.setReceiptId(t.receiptId());
    d.setParentTaskId(t.parentTaskId());
    d.setSku(t.sku());
    d.setLotId(t.lotId());
    d.setLocationCode(t.locationCode());
    d.setPlannedQty(t.plannedQty());
    d.setDoneQty(t.doneQty());
    d.setState(InboundTask.StateEnum.fromValue(t.state()));
    d.setVersion(t.version());
    return d;
  }
}
