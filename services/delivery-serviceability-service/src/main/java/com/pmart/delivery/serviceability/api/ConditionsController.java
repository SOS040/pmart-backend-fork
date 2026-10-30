package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.ConditionsApi;
import com.pmart.delivery.serviceability.api.generated.model.DeliveryConditions;
import com.pmart.delivery.serviceability.api.generated.model.DeliveryConditionsMaxWeightG;
import com.pmart.delivery.serviceability.api.generated.model.DeliveryConditionsTimeRestriction;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityCheckAsOf;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import com.pmart.delivery.serviceability.domain.DeliveryPolicy;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ConditionsApi — MFC 배송 가능 조건(확정값만, 승인 대기 값은 NOT_SET). 변환만 한다. */
@RestController
public class ConditionsController implements ConditionsApi {

  private final ServiceabilityService service;
  private final Clock clock;

  public ConditionsController(ServiceabilityService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<DeliveryConditions> getDeliveryConditions(String mfcId) {
    int version = service.servingVersion(mfcId);
    return ResponseEntity.ok(
        new DeliveryConditions()
            .mfcId(mfcId)
            .policyVersion(version)
            .deliveryModes(
                DeliveryPolicy.MODES.stream()
                    .map(DeliveryConditions.DeliveryModesEnum::fromValue)
                    .toList())
            .vehicleTypes(List.of())
            .maxWeightG(
                new DeliveryConditionsMaxWeightG()
                    .state(DeliveryConditionsMaxWeightG.StateEnum.VALUE)
                    .value(DeliveryPolicy.MAX_WEIGHT_PER_DELIVERY_G))
            .maxVolumeL(notSet())
            .coldChainAvailable(notSet())
            .timeRestriction(
                new DeliveryConditionsTimeRestriction()
                    .state(DeliveryConditionsTimeRestriction.StateEnum.WINDOW)
                    .start(DeliveryPolicy.NO_DELIVERY_START)
                    .end(DeliveryPolicy.NO_DELIVERY_END))
            .asOf(
                new ServiceabilityCheckAsOf()
                    .asOf(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .sourceVersion(version)
                    .freshness(ServiceabilityCheckAsOf.FreshnessEnum.CURRENT)));
  }

  private static DeliveryConditionsMaxWeightG notSet() {
    return new DeliveryConditionsMaxWeightG().state(DeliveryConditionsMaxWeightG.StateEnum.NOT_SET);
  }
}
