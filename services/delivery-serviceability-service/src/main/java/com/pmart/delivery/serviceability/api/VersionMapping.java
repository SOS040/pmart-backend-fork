package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersion;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersionZonesInner;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersionZonesInnerGeometry;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.VersionRow;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.ZoneRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 매핑 버전 응답 변환. REJECTED 는 계약 enum 에 없으면 상태를 비운다. */
@Component
class VersionMapping {

  private final ServiceabilityService service;
  private final ObjectMapper mapper;

  VersionMapping(ServiceabilityService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @SuppressWarnings("unchecked")
  ServiceabilityVersion view(VersionRow v, boolean includeGeometry) {
    List<ServiceabilityVersionZonesInner> zones = new ArrayList<>();
    for (ZoneRow z : service.zones(v.version())) {
      ServiceabilityVersionZonesInner zi =
          new ServiceabilityVersionZonesInner().zoneId(z.zoneId()).mfcId(z.mfcId()).name(z.name());
      if (includeGeometry) {
        JsonNode g = mapper.readTree(z.geometry());
        zi.geometry(
            new ServiceabilityVersionZonesInnerGeometry()
                .type(
                    ServiceabilityVersionZonesInnerGeometry.TypeEnum.fromValue(
                        g.path("type").asString()))
                .coordinates(mapper.convertValue(g.path("coordinates"), List.class)));
      }
      zones.add(zi);
    }
    ServiceabilityVersion out =
        new ServiceabilityVersion()
            .version(v.version())
            .approvalRef(v.approvalId())
            .approvedAt(odt(v.approvedAt()))
            .effectiveFrom(odt(v.effectiveFrom()))
            .supersededAt(odt(v.supersededAt()))
            .testAddressesVerified(v.testVerified())
            .zones(zones)
            .changeSummary(v.changeSummary());
    for (ServiceabilityVersion.StateEnum s : ServiceabilityVersion.StateEnum.values()) {
      if (s.getValue().equals(v.state())) {
        out.state(s);
      }
    }
    return out;
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
