package com.pmart.delivery.capacity.api;

import com.pmart.delivery.capacity.api.generated.HoldsApi;
import com.pmart.delivery.capacity.api.generated.model.ArrivalWindow;
import com.pmart.delivery.capacity.api.generated.model.ConfirmSlotHold200Response;
import com.pmart.delivery.capacity.api.generated.model.ConfirmSlotHoldRequest;
import com.pmart.delivery.capacity.api.generated.model.HoldCommandResult;
import com.pmart.delivery.capacity.api.generated.model.ReleaseSlotHoldRequest;
import com.pmart.delivery.capacity.api.generated.model.SlotHold;
import com.pmart.delivery.capacity.api.generated.model.SlotHoldRequest;
import com.pmart.delivery.capacity.api.generated.model.SlotHoldState;
import com.pmart.delivery.capacity.application.SlotHoldService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 HoldsApi — 슬롯 점유·확정·해제(내부 호출(토큰 없음) 전용 /internal). 업무 규칙은 {@link SlotHoldService}, 여기서는 변환만
 * 한다.
 */
@RestController
public class HoldsController implements HoldsApi {

  private final SlotHoldService holds;

  public HoldsController(SlotHoldService holds) {
    this.holds = holds;
  }

  @Override
  public ResponseEntity<SlotHold> createSlotHold(
      String idempotencyKey,
      String xSourceSystem,
      SlotHoldRequest body,
      @Nullable String traceparent) {
    ArrivalWindow w = body.getWindow();
    var hold =
        holds.create(
            idempotencyKey,
            xSourceSystem,
            new SlotHoldService.CreateHold(
                body.getOrderId(),
                body.getDraftId(),
                body.getSagaId(),
                body.getMfcId(),
                w.getDate(),
                w.getStart(),
                w.getEnd(),
                body.getTtlSeconds() == null ? 600 : body.getTtlSeconds(),
                body.getNewSlotFor()));
    // 계약: 새 점유와 같은 키 재전송(기존 점유) 모두 201
    return ResponseEntity.status(HttpStatus.CREATED).body(hold(hold));
  }

  @Override
  public ResponseEntity<SlotHold> getSlotHold(String holdId) {
    return ResponseEntity.ok(hold(holds.get(holdId)));
  }

  @Override
  public ResponseEntity<ConfirmSlotHold200Response> confirmSlotHold(
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmSlotHoldRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        command(
            holds.confirm(
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                new SlotHoldService.ConfirmHold(holdId, body.getOrderId(), body.getSagaId()))));
  }

  @Override
  public ResponseEntity<ConfirmSlotHold200Response> releaseSlotHold(
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseSlotHoldRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        command(
            holds.release(
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                new SlotHoldService.ReleaseHold(
                    holdId,
                    body.getReason().getValue(),
                    body.getSagaId(),
                    body.getApprovalRef()))));
  }

  @Override
  public ResponseEntity<HoldCommandResult> getSlotHoldConfirmation(
      String holdId, String requestId) {
    return ResponseEntity.ok(
        result(holds.commandResult(holdId, SlotHoldService.CONFIRM, requestId)));
  }

  @Override
  public ResponseEntity<HoldCommandResult> getSlotHoldRelease(String holdId, String requestId) {
    return ResponseEntity.ok(
        result(holds.commandResult(holdId, SlotHoldService.RELEASE, requestId)));
  }

  private static SlotHold hold(com.pmart.delivery.capacity.domain.SlotHold h) {
    return new SlotHold()
        .holdId(h.holdId())
        .version(h.version())
        .orderId(h.orderId())
        .mfcId(h.mfcId())
        .slotId(h.slotId())
        .window(
            new ArrivalWindow()
                .date(h.window().date())
                .start(h.window().start())
                .end(h.window().end()))
        .state(SlotHoldState.fromValue(h.state().name()))
        .heldAt(time(h.heldAt()))
        .expiresAt(time(h.expiresAt()))
        .confirmedAt(time(h.confirmedAt()))
        .releasedAt(time(h.releasedAt()))
        .releaseReason(h.releaseReason())
        .replaces(h.replaces());
  }

  private static ConfirmSlotHold200Response command(SlotHoldService.CommandResult r) {
    return new ConfirmSlotHold200Response()
        .status(ConfirmSlotHold200Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.holdId())
        .version(r.version())
        .retryable(false)
        .occurredAt(time(r.occurredAt()));
  }

  private static HoldCommandResult result(SlotHoldService.CommandResult r) {
    return new HoldCommandResult()
        .requestId(r.requestId())
        .holdId(r.holdId())
        .status(HoldCommandResult.StatusEnum.fromValue(r.status().name()))
        .resultingState(SlotHoldState.fromValue(r.resultingState().name()))
        .version(r.version())
        .occurredAt(time(r.occurredAt()));
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
