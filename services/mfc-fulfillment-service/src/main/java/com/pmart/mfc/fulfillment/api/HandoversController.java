package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.HandoversApi;
import com.pmart.mfc.fulfillment.api.generated.model.AuthorizePackageHandoverRequest;
import com.pmart.mfc.fulfillment.api.generated.model.CommitPackageHandoverRequest;
import com.pmart.mfc.fulfillment.api.generated.model.FenceState;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverAuthorization;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverCommitResult;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverResolution;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverResolutionFence;
import com.pmart.mfc.fulfillment.api.generated.model.PackageState;
import com.pmart.mfc.fulfillment.api.generated.model.ShipmentState;
import com.pmart.mfc.fulfillment.application.HandoverService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 HandoversApi(라이더 인계, 슬라이스 6c — ADR-0047). 업무 규칙은 {@link HandoverService}·{@code
 * domain/HandoverRules}, 여기서는 요청·응답 변환만 한다. 재포장은 인터페이스 default 의 501.
 */
@RestController
public class HandoversController implements HandoversApi {

  private final HandoverService handovers;

  public HandoversController(HandoverService handovers) {
    this.handovers = handovers;
  }

  @Override
  public ResponseEntity<HandoverAuthorization> authorizePackageHandover(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      AuthorizePackageHandoverRequest body,
      @Nullable Integer xExpectedVersion) {
    var a =
        handovers.authorize(
            packageId,
            idempotencyKey,
            xSourceSystem,
            new HandoverService.AuthorizeCommand(
                body.getRiderId(), body.getDeliveryId(), body.getScannedCode()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new HandoverAuthorization()
                .handoverAuthorizationId(a.handoverAuthorizationId())
                .packageId(a.packageId())
                .packageVersion(a.packageVersion())
                .fenceVersion(a.fenceVersion())
                .riderId(a.riderId())
                .deliveryId(a.deliveryId())
                .fenceExpiresAt(time(a.fenceExpiresAt()))
                .authorizedAt(time(a.authorizedAt())));
  }

  @Override
  public ResponseEntity<HandoverCommitResult> commitPackageHandover(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      CommitPackageHandoverRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        handovers.commit(
            packageId,
            new HandoverService.CommitCommand(
                body.getHandoverAuthorizationId(), body.getFenceVersion(), body.getRiderId()));
    HandoverCommitResult out =
        new HandoverCommitResult()
            .status(HandoverCommitResult.StatusEnum.fromValue(r.status()))
            .requestId(idempotencyKey)
            .handoverId(r.handoverId())
            .inventoryCommitId(r.inventoryCommitId())
            .packageState(PackageState.fromValue(r.packageState()))
            .shipmentState(ShipmentState.fromValue(r.shipmentState()))
            .nextAction(r.nextAction());
    if (r.handedOverAt() != null) {
      out.setHandedOverAt(time(r.handedOverAt()));
    }
    return ResponseEntity.status(
            "APPLIED".equals(r.status()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
        .body(out);
  }

  @Override
  public ResponseEntity<HandoverResolution> getHandoverResolution(
      String packageId, @Nullable String stopRequestId) {
    var r = handovers.resolution(packageId, stopRequestId);
    var p = r.pkg();
    String fenceState =
        "HANDOVER_IN_PROGRESS".equals(p.fenceState()) && !r.fenceLive()
            ? "EXPIRED"
            : p.fenceState();
    String apply =
        r.handover() == null
            ? "NOT_REQUESTED"
            : switch (r.handover().state()) {
              case "APPLIED" -> "APPLIED";
              case "REJECTED" -> "REJECTED";
              case "UNKNOWN_RESULT" -> "UNKNOWN_RESULT";
              default -> "PROCESSING";
            };
    HandoverResolutionFence fence =
        new HandoverResolutionFence()
            .state(FenceState.fromValue(fenceState))
            .handoverAuthorizationId(p.authorizationId())
            .fenceVersion(p.fenceVersion());
    if (p.fenceExpiresAt() != null) {
      fence.setExpiresAt(time(p.fenceExpiresAt()));
    }
    return ResponseEntity.ok(
        new HandoverResolution()
            .packageId(p.packageId())
            .packageVersion(p.packageVersion())
            .packageState(PackageState.fromValue(p.state()))
            .verdict(HandoverResolution.VerdictEnum.fromValue(r.verdict().name()))
            .fence(fence)
            .handoverId(r.handover() == null ? null : r.handover().handoverId())
            .inventoryCommitId(r.handover() == null ? null : r.handover().inventoryCommitId())
            .inventoryApplyStatus(HandoverResolution.InventoryApplyStatusEnum.fromValue(apply))
            .stopRequestId(r.stopRequestId()));
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
