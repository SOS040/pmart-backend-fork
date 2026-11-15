package com.pmart.backoffice.bff.infrastructure;

import com.pmart.backoffice.bff.application.BffProperties;
import com.pmart.common.web.Downstream;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 처리 대기(BO-12, 2026-11-16) 원천 호출. order-orchestration-service 는 서비스 간 내부 경로(토큰 없음, ADR-0026),
 * order-service 이행 확인 필요는 운영자 API 라 <b>호출한 운영자의 토큰</b>을 넘긴다. 실패는 {@link Downstream} 규칙(미전달·503 →
 * DEPENDENCY_UNAVAILABLE, 4xx 그대로).
 */
@Component
public class PendingWorkClients {

  private final RestClient orchestration;
  private final RestClient order;
  private final ObjectMapper mapper;

  public PendingWorkClients(RestClient.Builder builder, BffProperties props, ObjectMapper mapper) {
    this.orchestration = builder.clone().baseUrl(props.orchestrationBaseUrl()).build();
    this.order = builder.clone().baseUrl(props.orderBaseUrl()).build();
    this.mapper = mapper;
  }

  public JsonNode stuckSagas(int size) {
    String raw =
        Downstream.call(
            "order-orchestration stuck-sagas",
            () ->
                orchestration
                    .get()
                    .uri(
                        u ->
                            u.path("/api/v1/internal/stuck-sagas").queryParam("size", size).build())
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }

  public JsonNode openFulfillmentIssues(String bearerToken) {
    String raw =
        Downstream.call(
            "order fulfillment-issues",
            () ->
                order
                    .get()
                    .uri(
                        u ->
                            u.path("/api/v1/fulfillment-issues")
                                .queryParam("state", "OPEN")
                                .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }

  /** 수동 조치 전달 — 대상 CommandResponse 그대로. */
  public JsonNode manualAction(String sagaId, String idempotencyKey, Map<String, Object> body) {
    String raw =
        Downstream.call(
            "order-orchestration manual-actions",
            () ->
                orchestration
                    .post()
                    .uri("/api/v1/internal/sagas/{id}/manual-actions", sagaId)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Source-System", "backoffice-bff")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
