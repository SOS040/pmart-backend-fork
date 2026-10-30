package com.pmart.product.content.api;

import com.pmart.product.content.api.generated.InternalApi;
import com.pmart.product.content.api.generated.model.ContentSnapshot;
import com.pmart.product.content.api.generated.model.GetProjectionStatusInternal200Response;
import com.pmart.product.content.application.ContentQueries;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 계약 InternalApi — 서비스 간 조회(서비스 토큰). 업무 규칙은 application 에 두고 여기서는 변환만 한다. */
@RestController
public class InternalController implements InternalApi {

  private final ContentQueries queries;
  private final ObjectMapper mapper;

  public InternalController(ContentQueries queries, ObjectMapper mapper) {
    this.queries = queries;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ContentSnapshot> getContentSnapshotInternal(
      String productId, @Nullable String sku) {
    return ResponseEntity.ok(
        mapper.convertValue(queries.snapshot(productId, sku), ContentSnapshot.class));
  }

  @Override
  public ResponseEntity<GetProjectionStatusInternal200Response> getProjectionStatusInternal(
      String productId) {
    return ResponseEntity.ok(
        mapper.convertValue(
            queries.projectionStatus(productId), GetProjectionStatusInternal200Response.class));
  }
}
