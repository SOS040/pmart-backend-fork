package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.AssignmentsApi;
import com.pmart.mfc.capacity.api.generated.model.AssignTaskRequest;
import com.pmart.mfc.capacity.api.generated.model.Assignment;
import com.pmart.mfc.capacity.api.generated.model.GetReceivingCapabilities200ResponseAsOf;
import com.pmart.mfc.capacity.api.generated.model.ListAssignments200Response;
import com.pmart.mfc.capacity.application.AssignmentService;
import com.pmart.mfc.capacity.application.AssignmentService.AssignCommand;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 AssignmentsApi. 수동 배정·조회(슬라이스 6b). 계획(OR-Tools)은 아직 구현하지 않아 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class AssignmentsController implements AssignmentsApi {

  private final AssignmentService service;

  public AssignmentsController(AssignmentService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Assignment> assignTask(
      String idempotencyKey,
      String xSourceSystem,
      AssignTaskRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service.assign(
            idempotencyKey,
            xSourceSystem,
            new AssignCommand(
                body.getMfcId(),
                body.getTaskId(),
                body.getTaskType().getValue(),
                body.getWorkerId(),
                body.getReason(),
                xExpectedVersion,
                body.getTemperatureZone() == null ? null : body.getTemperatureZone().getValue()));
    Assignment out = dto(r.assignment());
    out.setWarnings(r.warnings());
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(out);
  }

  @Override
  public ResponseEntity<ListAssignments200Response> listAssignments(
      String mfcId,
      @Nullable LocalDate date,
      @Nullable String workerId,
      @Nullable String taskId,
      @Nullable String state) {
    var out = new ListAssignments200Response();
    out.setItems(
        service.list(mfcId, taskId, workerId, state, date).stream()
            .map(AssignmentsController::dto)
            .toList());
    var asOf = new GetReceivingCapabilities200ResponseAsOf();
    asOf.setAsOf(Instant.now().atOffset(ZoneOffset.UTC));
    asOf.setFreshness(GetReceivingCapabilities200ResponseAsOf.FreshnessEnum.CURRENT);
    out.setAsOf(asOf);
    return ResponseEntity.ok(out);
  }

  static Assignment dto(AssignmentRow r) {
    var a = new Assignment();
    a.setAssignmentId(r.assignmentId());
    a.setMfcId(r.mfcId());
    a.setTaskId(r.taskId());
    a.setTaskType(r.taskType());
    a.setWorkerId(r.workerId());
    a.setAssignmentVersion(r.assignmentVersion());
    a.setState(Assignment.StateEnum.fromValue(r.state()));
    a.setPlanId(r.planId());
    a.setAssignedBy(r.assignedBy());
    a.setAssignedAt(r.assignedAt().atOffset(ZoneOffset.UTC));
    return a;
  }
}
