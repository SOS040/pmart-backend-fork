package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.ServiceabilityApi;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityCheck;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityCheckAsOf;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityCheckOverlappingZonesInner;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityCheckRequest;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityResult;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.CheckRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 계약 ServiceabilityApi — 주소 판정(좌표 기준). 업무 규칙은 application 에 있고 변환만 한다. */
@RestController
public class ServiceabilityController implements ServiceabilityApi {

  private final ServiceabilityService service;
  private final ObjectMapper mapper;

  public ServiceabilityController(ServiceabilityService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ServiceabilityCheck> createServiceabilityCheck(
      String idempotencyKey,
      String xSourceSystem,
      ServiceabilityCheckRequest body,
      @Nullable String traceparent) {
    var a = body.getAddress();
    String checkId =
        service.check(
            idempotencyKey,
            xSourceSystem,
            new ServiceabilityService.CheckInput(
                a.getLat() == null ? null : a.getLat().doubleValue(),
                a.getLng() == null ? null : a.getLng().doubleValue(),
                body.getMappingVersion(),
                body.getPurpose() == null ? null : body.getPurpose().getValue()),
            body);
    return ResponseEntity.ok(view(service.result(checkId)));
  }

  @Override
  public ResponseEntity<ServiceabilityCheck> getServiceabilityCheck(String checkId) {
    return ResponseEntity.ok(view(service.result(checkId)));
  }

  private ServiceabilityCheck view(CheckRow c) {
    List<ServiceabilityCheckOverlappingZonesInner> overlapping = new ArrayList<>();
    for (JsonNode o : mapper.readTree(c.overlapping())) {
      overlapping.add(
          new ServiceabilityCheckOverlappingZonesInner()
              .zoneId(o.path("zoneId").asString())
              .mfcId(o.path("mfcId").asString()));
    }
    List<String> missing = new ArrayList<>();
    mapper.readTree(c.missingFields()).forEach(m -> missing.add(m.asString()));
    OffsetDateTime at = OffsetDateTime.ofInstant(c.evaluatedAt(), ZoneOffset.UTC);
    return new ServiceabilityCheck()
        .checkId(c.checkId())
        .result(ServiceabilityResult.fromValue(c.result()))
        .mfcId(c.mfcId())
        .mappingVersion(c.mappingVersion())
        .zoneId(c.zoneId())
        .overlappingZones(overlapping)
        .missingFields(missing)
        .evaluatedAt(at)
        .asOf(
            new ServiceabilityCheckAsOf()
                .asOf(at)
                .sourceVersion(c.mappingVersion())
                .freshness(ServiceabilityCheckAsOf.FreshnessEnum.CURRENT));
  }
}
