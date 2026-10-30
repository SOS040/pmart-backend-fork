package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.MappingVersionsApi;
import com.pmart.delivery.serviceability.api.generated.model.CreateServiceabilityVersion201Response;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersion;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersionDraft;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 계약 MappingVersionsApi — 권역 매핑 버전 초안 등록(운영자)·조회. 변환만 한다. */
@RestController
public class MappingVersionsController implements MappingVersionsApi {

  private final ServiceabilityService service;
  private final VersionMapping mapping;
  private final ObjectMapper mapper;

  public MappingVersionsController(
      ServiceabilityService service, VersionMapping mapping, ObjectMapper mapper) {
    this.service = service;
    this.mapping = mapping;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<CreateServiceabilityVersion201Response> createServiceabilityVersion(
      String idempotencyKey, String xSourceSystem, ServiceabilityVersionDraft body) {
    int version = service.createDraft(idempotencyKey, xSourceSystem, mapper.valueToTree(body));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new CreateServiceabilityVersion201Response()
                .version(mapping.view(service.version(version), true))
                .approvalRequest(service.approvalRequest(version)));
  }

  @Override
  public ResponseEntity<ServiceabilityVersion> getServiceabilityVersionForOperator(
      Integer version) {
    return ResponseEntity.ok(mapping.view(service.version(version), true));
  }
}
